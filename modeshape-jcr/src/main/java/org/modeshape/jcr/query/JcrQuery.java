/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.jcr.query;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.Query;
import org.modeshape.common.annotation.NotThreadSafe;
import org.modeshape.common.util.CheckArg;
import org.modeshape.common.util.StringUtil;
import org.modeshape.jcr.JcrI18n;
import org.modeshape.jcr.query.model.BindVariableName;
import org.modeshape.jcr.query.model.LiteralValue;
import org.modeshape.jcr.query.model.QueryCommand;
import org.modeshape.jcr.query.model.Subquery;
import org.modeshape.jcr.query.model.Visitors;
import org.modeshape.jcr.query.parse.QueryParser;
import org.modeshape.jcr.query.plan.PlanHints;
import org.modeshape.jcr.value.Path;

/**
 * Implementation of {@link Query} that represents a {@link QueryCommand query command}.
 */
@NotThreadSafe
public class JcrQuery extends JcrAbstractQuery {

    private QueryCommand query;
    private final PlanHints hints;
    private final Map<String, Object> variables;
    private volatile Set<String> variableNames;
    private final AtomicReference<CancellableQuery> executingQuery = new AtomicReference<CancellableQuery>();

    /**
     * Creates a new JCR {@link Query} by specifying the query statement itself, the language in which the query is stated, the
     * {@link QueryCommand} representation and, optionally, the node from which the query was loaded. The language must be a
     * string from among those returned by {@code QueryManager#getSupportedQueryLanguages()}.
     * 
     * @param context the context that was used to create this query and that will be used to execute this query; may not be null
     * @param statement the original statement as supplied by the client; may not be null
     * @param language the language obtained from the {@link QueryParser}; may not be null
     * @param query the parsed query representation; may not be null
     * @param hints any hints that are to be used; may be null if there are no hints
     * @param storedAtPath the path at which this query was stored, or null if this is not a stored query
     */
    public JcrQuery( JcrQueryContext context,
                     String statement,
                     String language,
                     QueryCommand query,
                     PlanHints hints,
                     Path storedAtPath ) {
        super(context, statement, language, storedAtPath);
        assert query != null;
        this.query = query;
        this.hints = hints;
        this.variables = new HashMap<String, Object>();
    }

    @Override
    public void includeSystemContent( boolean includeSystemContent ) {
        this.hints.includeSystemContent = includeSystemContent;
    }

    protected QueryCommand query() {
        return query;
    }

    /**
     * Get the underlying and immutable Abstract Query Model representation of this query.
     * 
     * @return the AQM representation; never null
     */
    public QueryCommand getAbstractQueryModel() {
        return query;
    }

    @Override
    public String getAbstractQueryModelRepresentation() {
        return query.toString();
    }

    /**
     * {@inheritDoc}
     * 
     * @see javax.jcr.query.Query#execute()
     */
    @SuppressWarnings( "deprecation" )
    @Override
    public org.modeshape.jcr.api.query.QueryResult execute() throws RepositoryException {
        context.checkValid();
        final long start = System.nanoTime();
        // Create an executable query and set it on this object ...
        CancellableQuery newExecutable = context.createExecutableQuery(query, hints, variables);
        CancellableQuery executable = executingQuery.getAndSet(newExecutable);
        if (executable == null) {
            // We are the first to call 'execute()', so use our newly-created one ...
            executable = newExecutable;
        }
        // otherwise, some other thread called execute, so we can use it and just wait for the results ...
        final QueryResults result = executable.execute(); // may be cancelled

        // And reset the reference to null (if not already set to something else) ...
        executingQuery.compareAndSet(executable, null);

        checkForProblems(result.getProblems());
        context.recordDuration(Math.abs(System.nanoTime() - start), TimeUnit.NANOSECONDS, statement, language);
        return new JcrQueryResult(context, statement, result, hints.restartable, hints.rowsKeptInMemory);
    }

    @SuppressWarnings( "deprecation" )
    @Override
    public org.modeshape.jcr.api.query.QueryResult explain() throws RepositoryException {
        context.checkValid();

        // Set to only compute the plan and then create an executable query ...
        PlanHints hints = this.hints.clone();
        hints.planOnly = true;
        CancellableQuery planOnlyExecutable = context.createExecutableQuery(query, hints, variables);

        // otherwise, some other thread called execute, so we can use it and just wait for the results ...
        final QueryResults result = planOnlyExecutable.execute(); // may be cancelled

        checkForProblems(result.getProblems());
        return new JcrQueryResult(context, statement, result, false, 0);
    }

    @Override
    public boolean cancel() {
        CancellableQuery executing = executingQuery.get();
        if (executing != null) {
            boolean cancelled = executing.cancel();
            // Remove the reference (if still there as we obtained it several lines up) ...
            executingQuery.compareAndSet(executing, null);
            return cancelled;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return language + " -> " + statement + "\n" + StringUtil.createString(' ', Math.min(language.length() - 3, 0))
               + "AQM -> " + query;
    }

    @Override
    public void bindValue( String varName,
                           Value value ) throws IllegalArgumentException, RepositoryException {
        CheckArg.isNotNull(varName, "varName");
        CheckArg.isNotNull(value, "value");
        if (!variableNames().contains(varName) && !Subquery.isSubqueryVariableName(varName)) {
            throw new IllegalArgumentException(JcrI18n.noSuchVariableInQuery.text(varName, statement));
        }
        variables.put(varName, LiteralValue.rawValue(value));
    }

    @Override
    public String[] getBindVariableNames() {
        Set<String> names = variableNames();
        // Always return a new array for immutability reasons ...
        return names.toArray(new String[names.size()]);
    }

    protected final Set<String> variableNames() {
        // This is idempotent, so no need to lock ...
        if (variableNames == null) {
            // Walk the query to find the bind variables ...
            final Set<String> names = new HashSet<String>();
            Visitors.visitAll(query, new Visitors.AbstractVisitor() {
                @Override
                public void visit( BindVariableName obj ) {
                    names.add(obj.getBindVariableName());
                }
            });
            variableNames = names;
        }
        return variableNames;
    }

    @Override
    public void setLimit( long limit ) {
        if (limit > Integer.MAX_VALUE) limit = Integer.MAX_VALUE;
        query = query.withLimit((int)limit); // may not actually change if the limit matches the existing query
    }

    @Override
    public void setOffset( long offset ) {
        if (offset > Integer.MAX_VALUE) offset = Integer.MAX_VALUE;
        query = query.withOffset((int)offset); // may not actually change if the offset matches the existing query
    }
}
