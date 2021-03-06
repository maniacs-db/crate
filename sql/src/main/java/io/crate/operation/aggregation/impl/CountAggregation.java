/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation.aggregation.impl;

import com.google.common.collect.ImmutableList;
import io.crate.Streamer;
import io.crate.analyze.symbol.Function;
import io.crate.analyze.symbol.Literal;
import io.crate.analyze.symbol.Symbol;
import io.crate.breaker.RamAccountingContext;
import io.crate.metadata.*;
import io.crate.operation.Input;
import io.crate.operation.aggregation.AggregationFunction;
import io.crate.types.DataType;
import io.crate.types.DataTypeFactory;
import io.crate.types.DataTypes;
import io.crate.types.FixedWidthType;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.List;

public class CountAggregation extends AggregationFunction<CountAggregation.LongState, Long> {

    public static final String NAME = "count";
    private final FunctionInfo info;
    private final boolean hasArgs;

    static {
        DataTypes.register(CountAggregation.LongStateType.ID, CountAggregation.LongStateType.INSTANCE);
    }

    public static final FunctionInfo COUNT_STAR_FUNCTION = new FunctionInfo(new FunctionIdent(NAME,
        ImmutableList.<DataType>of()), DataTypes.LONG, FunctionInfo.Type.AGGREGATE);

    public static void register(AggregationImplModule mod) {
        mod.register(NAME, new CountAggregationFunctionResolver());
    }

    private static class CountAggregationFunctionResolver implements DynamicFunctionResolver {

        @Override
        public FunctionImplementation<Function> getForTypes(List<DataType> dataTypes) throws IllegalArgumentException {
            if (dataTypes.size() == 0) {
                return new CountAggregation(COUNT_STAR_FUNCTION, false);
            } else {
                return new CountAggregation(
                    new FunctionInfo(new FunctionIdent(NAME, dataTypes),
                        DataTypes.LONG, FunctionInfo.Type.AGGREGATE), true);
            }
        }
    }

    private CountAggregation(FunctionInfo info, boolean hasArgs) {
        this.info = info;
        this.hasArgs = hasArgs;
    }

    @Override
    public LongState iterate(RamAccountingContext ramAccountingContext, LongState state, Input... args) {
        if (!hasArgs || args[0].value() != null) {
            return state.add(1L);
        }
        return state;
    }

    @Override
    public LongState newState(RamAccountingContext ramAccountingContext) {
        ramAccountingContext.addBytes(LongStateType.INSTANCE.fixedSize());
        return new LongState();
    }

    @Override
    public FunctionInfo info() {
        return info;
    }

    @Override
    public Symbol normalizeSymbol(Function function, StmtCtx stmtCtx) {
        assert (function.arguments().size() <= 1);

        if (function.arguments().size() == 1) {
            if (function.arguments().get(0).symbolType().isValueSymbol()) {
                if ((function.arguments().get(0)).valueType() == DataTypes.UNDEFINED) {
                    return Literal.newLiteral(0L);
                } else {
                    return new Function(COUNT_STAR_FUNCTION, ImmutableList.<Symbol>of());
                }
            }
        }
        return function;
    }

    @Override
    public DataType partialType() {
        return LongStateType.INSTANCE;
    }

    @Override
    public LongState reduce(RamAccountingContext ramAccountingContext, LongState state1, LongState state2) {
        return state1.merge(state2);
    }

    @Override
    public Long terminatePartial(RamAccountingContext ramAccountingContext, LongState state) {
        return state.value;
    }

    public static class LongState implements Comparable<CountAggregation.LongState> {

        long value = 0L;

        public LongState() {
        }

        public LongState(long value) {
            this.value = value;
        }

        public LongState add(long value) {
            this.value = this.value + value;
            return this;
        }

        public LongState merge(LongState otherState) {
            add(otherState.value);
            return this;
        }

        @Override
        public int compareTo(LongState o) {
            if (o == null) {
                return 1;
            } else {
                return Long.compare(value, o.value);
            }
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    public static class LongStateType extends DataType<LongState>
        implements FixedWidthType, Streamer<LongState>, DataTypeFactory {

        public static final int ID = 16384;
        public static final LongStateType INSTANCE = new LongStateType();

        @Override
        public int id() {
            return ID;
        }

        @Override
        public String getName() {
            return "long_state";
        }

        @Override
        public Streamer<?> streamer() {
            return this;
        }

        @Override
        public LongState value(Object value) throws IllegalArgumentException, ClassCastException {
            return (LongState) value;
        }

        @Override
        public int compareValueTo(LongState val1, LongState val2) {
            if (val1 == null) {
                return -1;
            } else {
                return val1.compareTo(val2);
            }
        }

        @Override
        public LongState readValueFrom(StreamInput in) throws IOException {
            return new CountAggregation.LongState(in.readVLong());
        }

        @Override
        public void writeValueTo(StreamOutput out, Object v) throws IOException {
            LongState longState = (LongState) v;
            out.writeVLong(longState.value);
        }

        @Override
        public DataType<?> create() {
            return INSTANCE;
        }

        @Override
        public int fixedSize() {
            return DataTypes.LONG.fixedSize();
        }
    }
}
