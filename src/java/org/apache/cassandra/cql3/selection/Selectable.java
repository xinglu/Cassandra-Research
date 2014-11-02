/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cassandra.cql3.selection;

import java.util.List;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.cql3.functions.Function;
import org.apache.cassandra.cql3.functions.FunctionName;
import org.apache.cassandra.cql3.functions.Functions;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.UserType;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.commons.lang3.text.StrBuilder;

//对应Select语句的:
//见Cql.g
//unaliasedSelector returns [Selectable s]
//      @init { Selectable tmp = null; }
//      :  ( c=cident                                  { tmp = c; }
//         | K_WRITETIME '(' c=cident ')'              { tmp = new Selectable.WritetimeOrTTL(c, true); }
//         | K_TTL       '(' c=cident ')'              { tmp = new Selectable.WritetimeOrTTL(c, false); }
//         | f=functionName args=selectionFunctionArgs { tmp = new Selectable.WithFunction(f, args); }
//         ) ( '.' fi=cident { tmp = new Selectable.WithFieldSelection(tmp, fi); } )* { $s = tmp; }
//      ;
//在CqlParser中生成下面这些类的实例
public abstract class Selectable
{
    public abstract Selector.Factory newSelectorFactory(CFMetaData cfm, List<ColumnDefinition> defs)
            throws InvalidRequestException;

    protected static int addAndGetIndex(ColumnDefinition def, List<ColumnDefinition> l)
    {
        int idx = l.indexOf(def);
        if (idx < 0)
        {
            idx = l.size();
            l.add(def);
        }
        return idx;
    }

    public static class WritetimeOrTTL extends Selectable
    {
        public final ColumnIdentifier id;
        public final boolean isWritetime;

        public WritetimeOrTTL(ColumnIdentifier id, boolean isWritetime)
        {
            this.id = id;
            this.isWritetime = isWritetime;
        }

        @Override
        public String toString()
        {
            return (isWritetime ? "writetime" : "ttl") + "(" + id + ")";
        }

        public Selector.Factory newSelectorFactory(CFMetaData cfm,
                                                   List<ColumnDefinition> defs) throws InvalidRequestException
        {
            ColumnDefinition def = cfm.getColumnDefinition(id);
            if (def == null)
                throw new InvalidRequestException(String.format("Undefined name %s in selection clause", id));
            if (def.isPrimaryKeyColumn())
                throw new InvalidRequestException(
                        String.format("Cannot use selection function %s on PRIMARY KEY part %s",
                                      isWritetime ? "writeTime" : "ttl",
                                      def.name));
            if (def.type.isCollection())
                throw new InvalidRequestException(String.format("Cannot use selection function %s on collections",
                                                                isWritetime ? "writeTime" : "ttl"));

            return WritetimeOrTTLSelector.newFactory(def.name.toString(), addAndGetIndex(def, defs), isWritetime);
        }
    }

    public static class WithFunction extends Selectable
    {
        public final FunctionName functionName;
        public final List<Selectable> args;
        //可以这样SELECT token(user_id)，如果是token(user_id, f1)那么args.size>0
        //但不能这样SELECT token(20)
        public WithFunction(FunctionName functionName, List<Selectable> args)
        {
            this.functionName = functionName;
            this.args = args;
        }

        @Override
        public String toString()
        {
            return new StrBuilder().append(functionName)
                                   .append("(")
                                   .appendWithSeparators(args, ", ")
                                   .append(")")
                                   .toString();
        }

        public Selector.Factory newSelectorFactory(CFMetaData cfm,
                                                   List<ColumnDefinition> defs) throws InvalidRequestException
        {
            SelectorFactories factories  =
                    SelectorFactories.createFactoriesAndCollectColumnDefinitions(args, cfm, defs);

            // resolve built-in functions before user defined functions
            Function fun = Functions.get(cfm.ksName, functionName, factories.newInstances(), cfm.ksName, cfm.cfName);
            if (fun == null)
                throw new InvalidRequestException(String.format("Unknown function '%s'", functionName));
            if (fun.returnType() == null)
                throw new InvalidRequestException(String.format("Unknown function %s called in selection clause",
                                                                functionName));

            return AbstractFunctionSelector.newFactory(fun, factories);
        }
    }
    
    //见my.test.cql3.TypeTest中的例子
    public static class WithFieldSelection extends Selectable
    {
        public final Selectable selected;
        public final ColumnIdentifier field;

        //此构造函数在org.apache.cassandra.cql3.CqlParser.unaliasedSelector()调用
        public WithFieldSelection(Selectable selected, ColumnIdentifier field)
        {
            this.selected = selected;
            this.field = field;
        }

        @Override
        public String toString()
        {
            return String.format("%s.%s", selected, field);
        }

        public Selector.Factory newSelectorFactory(CFMetaData cfm,
                                                   List<ColumnDefinition> defs) throws InvalidRequestException
        {
            Selector.Factory factory = selected.newSelectorFactory(cfm, defs);
            AbstractType<?> type = factory.newInstance().getType();
            if (!(type instanceof UserType))
                throw new InvalidRequestException(
                        String.format("Invalid field selection: %s of type %s is not a user type",
                                      selected,
                                      type.asCQL3Type()));

            UserType ut = (UserType) type;
            for (int i = 0; i < ut.size(); i++)
            {
                if (!ut.fieldName(i).equals(field.bytes))
                    continue;
                return FieldSelector.newFactory(ut, i, factory);
            }
            throw new InvalidRequestException(String.format("%s of type %s has no field %s",
                                                            selected,
                                                            type.asCQL3Type(),
                                                            field));
        }
    }
}
