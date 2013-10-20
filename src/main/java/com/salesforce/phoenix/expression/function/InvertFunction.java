/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc. All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided that the following conditions are met: Redistributions of source code
 * must retain the above copyright notice, this list of conditions and the following disclaimer. Redistributions in
 * binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution. Neither the name of Salesforce.com nor the names
 * of its contributors may be used to endorse or promote products derived from this software without specific prior
 * written permission. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.expression.function;

import java.sql.SQLException;
import java.util.List;

import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;

import com.salesforce.phoenix.compile.KeyPart;
import com.salesforce.phoenix.expression.Expression;
import com.salesforce.phoenix.parse.FunctionParseNode.Argument;
import com.salesforce.phoenix.parse.FunctionParseNode.BuiltInFunction;
import com.salesforce.phoenix.query.KeyRange;
import com.salesforce.phoenix.schema.ColumnModifier;
import com.salesforce.phoenix.schema.PColumn;
import com.salesforce.phoenix.schema.PDataType;
import com.salesforce.phoenix.schema.tuple.Tuple;

@BuiltInFunction(name = InvertFunction.NAME, args = { @Argument() })
public class InvertFunction extends ScalarFunction {
    public static final String NAME = "INVERT";

    public InvertFunction() throws SQLException {}

    public InvertFunction(List<Expression> children) throws SQLException {
        super(children);
    }

    @Override
    public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
        if (!getChildExpression().evaluate(tuple, ptr)) { return false; }
        if (ptr.getLength() == 0) { return true; }
        byte[] buf = new byte[ptr.getLength()];
        ColumnModifier.SORT_DESC.apply(ptr.get(), ptr.getOffset(), buf, 0, ptr.getLength());
        ptr.set(buf);
        return true;
    }

    @Override
    public ColumnModifier getColumnModifier() {
        return getChildExpression().getColumnModifier() == null ? ColumnModifier.SORT_DESC : null;
    }

    @Override
    public PDataType getDataType() {
        return getChildExpression().getDataType();
    }

    @Override
    public Integer getMaxLength() {
        return getChildExpression().getMaxLength();
    }

    @Override
    public Integer getByteSize() {
        return getChildExpression().getByteSize();
    }

    @Override
    public boolean isNullable() {
        return getChildExpression().isNullable();
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * INVERT may be optimized through
     */
    @Override
    public int getKeyFormationTraversalIndex() {
        return 0;
    }

    /**
     * Invert the childPart key range
     */
    @Override
    public KeyPart newKeyPart(final KeyPart childPart) {
        return new KeyPart() {

            @Override
            public KeyRange getKeyRange(CompareOp op, Expression rhs) {
                KeyRange range = childPart.getKeyRange(op, rhs);
                byte[] lower = range.getLowerRange();
                if (!range.lowerUnbound()) {
                    lower = ColumnModifier.SORT_DESC.apply(lower, 0, lower.length);
                }
                byte[] upper = range.getUpperRange();
                if (!range.upperUnbound()) {
                    upper = ColumnModifier.SORT_DESC.apply(upper, 0, upper.length);
                }
                return KeyRange.getKeyRange(lower, range.isLowerInclusive(), upper, range.isUpperInclusive());
            }

            @Override
            public List<Expression> getExtractNodes() {
                return childPart.getExtractNodes();
            }

            @Override
            public PColumn getColumn() {
                return childPart.getColumn();
            }
        };
    }
    
    @Override
    public OrderPreserving preservesOrder() {
        return OrderPreserving.YES;
    }

    private Expression getChildExpression() {
        return children.get(0);
    }
}