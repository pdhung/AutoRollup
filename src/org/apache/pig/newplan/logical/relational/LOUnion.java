/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pig.newplan.logical.relational;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.pig.PigException;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.util.Pair;
import org.apache.pig.newplan.Operator;
import org.apache.pig.newplan.OperatorPlan;
import org.apache.pig.newplan.PlanVisitor;
import org.apache.pig.newplan.logical.expression.LogicalExpression;
import org.apache.pig.newplan.logical.relational.LogicalSchema.LogicalFieldSchema;

public class LOUnion extends LogicalRelationalOperator {
    private boolean onSchema;
    
    // uid mapping from output uid to input uid
    private List<Pair<Long, Long>> uidMapping = new ArrayList<Pair<Long, Long>>();
    
    public LOUnion(OperatorPlan plan) {
        super("LOUnion", plan);
    }
    
    public LOUnion(OperatorPlan plan, boolean onSchema) {
        this( plan );
        this.onSchema = onSchema;
    }
    
    public boolean isOnSchema() {
        return onSchema;
    }
    
    @Override
    public LogicalSchema getSchema() throws FrontendException {
        if (schema != null) {
            return schema;
        }
        
        List<Operator> inputs = plan.getPredecessors(this);
        // If any predecessor's schema is null, then the schema for union is null
        for (Operator input : inputs) {
            LogicalRelationalOperator op = (LogicalRelationalOperator)input;
            if( op.getSchema() == null ) {
                if( isOnSchema() ) {
                    String msg = "Schema of relation " + op.getAlias()
                        + " is null." 
                        + " UNION ONSCHEMA cannot be used with relations that"
                        + " have null schema.";
                    throw new FrontendException(this, msg, 1116, PigException.INPUT);

                } else {
                    return null;
                }
            }
        }
        
        LogicalSchema mergedSchema = null;
        LogicalSchema s0 = ((LogicalRelationalOperator)inputs.get(0)).getSchema();
        if ( inputs.size() == 1 )
            return schema = s0;
        
        if( isOnSchema() ) {
            mergedSchema = createMergedSchemaOnAlias( inputs );
        } else {
            LogicalSchema s1 = ((LogicalRelationalOperator)inputs.get(1)).getSchema();
            mergedSchema = LogicalSchema.merge(s0, s1, LogicalSchema.MergeMode.Union);
            if (mergedSchema==null)
                return null;
            
            // Merge schema
            for (int i=2;i<inputs.size();i++) {
                LogicalSchema otherSchema = ((LogicalRelationalOperator)inputs.get(i)).getSchema();
                if (mergedSchema==null || otherSchema==null)
                    return null;
                mergedSchema = LogicalSchema.merge(mergedSchema, otherSchema, LogicalSchema.MergeMode.Union);
                if (mergedSchema == null)
                    return null;
            }
        }

        // Bring back cached uid if any; otherwise, cache uid generated
        for (int i=0;i<s0.size();i++)
        {
            LogicalSchema.LogicalFieldSchema outputFieldSchema;
            if (onSchema) {
                outputFieldSchema = mergedSchema.getFieldSubNameMatch(s0.getField(i).alias);
            } else {
                outputFieldSchema = mergedSchema.getField(i);
            }
            long uid = -1;
            for (Pair<Long, Long> pair : uidMapping) {
                if (pair.second==s0.getField(i).uid) {
                    uid = pair.first;
                    break;
                }
            }
            if (uid==-1) {
                uid = LogicalExpression.getNextUid();
                for (Operator input : inputs) {
                    long inputUid;
                    LogicalFieldSchema matchedInputFieldSchema;
                    if (onSchema) {
                        matchedInputFieldSchema = ((LogicalRelationalOperator)input).getSchema().getFieldSubNameMatch(s0.getField(i).alias);
                        if (matchedInputFieldSchema!=null) {
                            inputUid = matchedInputFieldSchema.uid;
                            uidMapping.add(new Pair<Long, Long>(uid, inputUid));
                        }
                    }
                    else {
                        matchedInputFieldSchema = mergedSchema.getField(i);
                        inputUid = ((LogicalRelationalOperator)input).getSchema().getField(i).uid;
                        uidMapping.add(new Pair<Long, Long>(uid, inputUid));
                    }
                    
                }
            }

            outputFieldSchema.uid = uid;
        }
        
        return schema = mergedSchema;
    }

    /**
     * create schema for union-onschema
     */
    private LogicalSchema createMergedSchemaOnAlias(List<Operator> ops)
    throws FrontendException {
        ArrayList<LogicalSchema> schemas = new ArrayList<LogicalSchema>();
        for( Operator op : ops ){
            LogicalRelationalOperator lop = (LogicalRelationalOperator)op;
            LogicalSchema sch = lop.getSchema();
            for( LogicalFieldSchema fs : sch.getFields() ) {
                if(fs.alias == null){
                    String msg = "Schema of relation " + lop.getAlias()
                        + " has a null fieldschema for column(s). Schema :" + sch.toString(false);
                    throw new FrontendException( this, msg, 1116, PigException.INPUT );
                }
            }
            schemas.add( sch );
        }
        
        //create the merged schema
        LogicalSchema mergedSchema = null;
        try {
            mergedSchema = LogicalSchema.mergeSchemasByAlias( schemas );   
        } catch(FrontendException e)                 {
            String msg = "Error merging schemas for union operator : "
                + e.getMessage();
            throw new FrontendException(this, msg, 1116, PigException.INPUT, e);
        }
        
        return mergedSchema;
    }
    
    @Override
    public void accept(PlanVisitor v) throws FrontendException {
        if (!(v instanceof LogicalRelationalNodesVisitor)) {
            throw new FrontendException("Expected LogicalPlanVisitor", 2223);
        }
        ((LogicalRelationalNodesVisitor)v).visit(this);
    }

    @Override
    public boolean isEqual(Operator other) throws FrontendException {
        if (other != null && other instanceof LOUnion) { 
            return checkEquality((LOUnion)other);
        } else {
            return false;
        }
    }

    // Get input uids mapping to the output uid
    public Set<Long> getInputUids(long uid) {
        Set<Long> result = new HashSet<Long>();
        for (Pair<Long, Long> pair : uidMapping) {
            if (pair.first==uid)
                result.add(pair.second);
        }
        return result;
    }
    
    @Override
    public void resetUid() {
        uidMapping = new ArrayList<Pair<Long, Long>>();
    }
    
    public List<Operator> getInputs() {
        return plan.getPredecessors(this);
    }
    
    public List<Operator> getInputs(LogicalPlan plan) {
        return plan.getPredecessors(this);
    }
    
    public void setUnionOnSchema(boolean flag) {
        onSchema = flag;
    }
}
