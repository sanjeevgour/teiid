/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.query.validator;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.script.Compilable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import net.sf.saxon.om.Name11Checker;
import net.sf.saxon.om.QNameException;
import net.sf.saxon.trans.XPathException;

import org.teiid.api.exception.query.ExpressionEvaluationException;
import org.teiid.api.exception.query.QueryMetadataException;
import org.teiid.api.exception.query.QueryValidatorException;
import org.teiid.core.CoreConstants;
import org.teiid.core.TeiidComponentException;
import org.teiid.core.TeiidException;
import org.teiid.core.TeiidProcessingException;
import org.teiid.core.types.ArrayImpl;
import org.teiid.core.types.DataTypeManager;
import org.teiid.core.util.EquivalenceUtil;
import org.teiid.language.SQLConstants;
import org.teiid.metadata.AggregateAttributes;
import org.teiid.metadata.Table;
import org.teiid.query.QueryPlugin;
import org.teiid.query.eval.Evaluator;
import org.teiid.query.function.FunctionLibrary;
import org.teiid.query.function.FunctionMethods;
import org.teiid.query.function.source.XMLSystemFunctions;
import org.teiid.query.metadata.StoredProcedureInfo;
import org.teiid.query.metadata.SupportConstants;
import org.teiid.query.resolver.ProcedureContainerResolver;
import org.teiid.query.resolver.QueryResolver;
import org.teiid.query.resolver.util.ResolverUtil;
import org.teiid.query.sql.LanguageObject;
import org.teiid.query.sql.lang.*;
import org.teiid.query.sql.lang.ObjectTable.ObjectColumn;
import org.teiid.query.sql.lang.SetQuery.Operation;
import org.teiid.query.sql.lang.XMLTable.XMLColumn;
import org.teiid.query.sql.navigator.PreOrderNavigator;
import org.teiid.query.sql.proc.Block;
import org.teiid.query.sql.proc.BranchingStatement;
import org.teiid.query.sql.proc.BranchingStatement.BranchingMode;
import org.teiid.query.sql.proc.CommandStatement;
import org.teiid.query.sql.proc.CreateProcedureCommand;
import org.teiid.query.sql.proc.LoopStatement;
import org.teiid.query.sql.proc.Statement.Labeled;
import org.teiid.query.sql.proc.WhileStatement;
import org.teiid.query.sql.symbol.*;
import org.teiid.query.sql.symbol.AggregateSymbol.Type;
import org.teiid.query.sql.visitor.AggregateSymbolCollectorVisitor;
import org.teiid.query.sql.visitor.AggregateSymbolCollectorVisitor.AggregateStopNavigator;
import org.teiid.query.sql.visitor.ElementCollectorVisitor;
import org.teiid.query.sql.visitor.EvaluatableVisitor;
import org.teiid.query.sql.visitor.FunctionCollectorVisitor;
import org.teiid.query.sql.visitor.GroupCollectorVisitor;
import org.teiid.query.sql.visitor.SQLStringVisitor;
import org.teiid.query.sql.visitor.ValueIteratorProviderCollectorVisitor;
import org.teiid.query.validator.UpdateValidator.UpdateInfo;
import org.teiid.query.xquery.saxon.SaxonXQueryExpression;
import org.teiid.translator.SourceSystemFunctions;

public class ValidationVisitor extends AbstractValidationVisitor {

    private final class PositiveIntegerConstraint implements
			Reference.Constraint {
    	
    	private String msgKey;
    	
    	public PositiveIntegerConstraint(String msgKey) {
    		this.msgKey = msgKey;
		}
    	
		@Override
		public void validate(Object value) throws QueryValidatorException {
			if (((Integer)value).intValue() < 0) {
				 throw new QueryValidatorException(QueryPlugin.Event.TEIID30242, QueryPlugin.Util.getString(msgKey));
			}
		}
	}

	// State during validation
    private boolean isXML = false;	// only used for Query commands
    private boolean inQuery;
	private CreateProcedureCommand createProc;
    
    public void reset() {
        super.reset();
        this.isXML = false;
        this.inQuery = false;
        this.createProc = null;
    }

    // ############### Visitor methods for language objects ##################
    
    public void visit(BatchedUpdateCommand obj) {
        List<Command> commands = obj.getUpdateCommands();
        Command command = null;
        int type = 0;
        for (int i = 0; i < commands.size(); i++) {
            command = commands.get(i);
            type = command.getType();
            if (type != Command.TYPE_INSERT &&
                type != Command.TYPE_UPDATE &&
                type != Command.TYPE_DELETE &&
                type != Command.TYPE_QUERY) { // SELECT INTO command
                handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_batch_command"),command); //$NON-NLS-1$
            } else if (type == Command.TYPE_QUERY) {
                Into into = ((Query)command).getInto();
                if (into == null) {
                    handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_batch_command"),command); //$NON-NLS-1$
                }
            }
        }
    }

	public void visit(Delete obj) {
    	validateNoXMLUpdates(obj);
        GroupSymbol group = obj.getGroup();
        validateGroupSupportsUpdate(group);
        if (obj.getUpdateInfo() != null && obj.getUpdateInfo().isInherentDelete()) {
        	validateUpdate(obj, Command.TYPE_DELETE, obj.getUpdateInfo());
        }
    }

    public void visit(GroupBy obj) {
    	// Get list of all group by IDs
        List<Expression> groupBySymbols = obj.getSymbols();
        validateSortable(groupBySymbols);
        for (Expression expr : groupBySymbols) {
            if (!ValueIteratorProviderCollectorVisitor.getValueIteratorProviders(expr).isEmpty() || expr instanceof Constant || expr instanceof Reference) {
            	handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.groupby_subquery", expr), expr); //$NON-NLS-1$
            }
		}
    }
    
    @Override
    public void visit(GroupSymbol obj) {
    	try {
			if (this.getMetadata().isScalarGroup(obj.getMetadataID())) {
			    handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_scalar_group_reference", obj),obj); //$NON-NLS-1$    		
			}
		} catch (QueryMetadataException e) {
			handleException(e);
		} catch (TeiidComponentException e) {
			handleException(e);
		}
    }

    public void visit(Insert obj) {
        validateNoXMLUpdates(obj);
        validateGroupSupportsUpdate(obj.getGroup());
        validateInsert(obj);
        
        try {
			if (obj.isMerge()) {
				Collection keys = getMetadata().getUniqueKeysInGroup(obj.getGroup().getMetadataID());
				if (keys.isEmpty()) {
					handleValidationError(QueryPlugin.Util.gs(QueryPlugin.Event.TEIID31132, obj.getGroup()), obj);
				} else {
					Set<Object> keyCols = new LinkedHashSet<Object>(getMetadata().getElementIDsInKey(keys.iterator().next()));
					for (ElementSymbol es : obj.getVariables()) {
						keyCols.remove(es.getMetadataID());
					}
					if (!keyCols.isEmpty()) {
						handleValidationError(QueryPlugin.Util.gs(QueryPlugin.Event.TEIID31133, obj.getGroup(), obj.getVariables()), obj);
					}
				}
			}
		} catch (QueryMetadataException e1) {
			handleException(e1);
		} catch (TeiidComponentException e1) {
			handleException(e1);
		}
        
        if (obj.getQueryExpression() != null) {
        	validateMultisourceInsert(obj.getGroup());
        }
        if (obj.getUpdateInfo() != null && obj.getUpdateInfo().isInherentInsert()) {
        	validateUpdate(obj, Command.TYPE_INSERT, obj.getUpdateInfo());
        	try {
				if (obj.getUpdateInfo().findInsertUpdateMapping(obj, false) == null) {
					handleValidationError(QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30376, obj.getVariables()), obj);
				}
			} catch (QueryValidatorException e) {
				handleValidationError(e.getMessage(), obj);
			}
        }
    }

    @Override
    public void visit(OrderByItem obj) {
    	validateSortable(obj.getSymbol());
    }
    
    public void visit(Query obj) {
        validateHasProjectedSymbols(obj);
        if(isXMLCommand(obj)) {
            //no temp table (Select Into) allowed
            if(obj.getInto() != null){
                handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0069"),obj); //$NON-NLS-1$
            }

        	this.isXML = true;
	        validateXMLQuery(obj);
        } else {
        	this.inQuery = true;
            validateAggregates(obj);

            //if it is select with no from, should not have ScalarSubQuery
            if(obj.getSelect() != null && obj.getFrom() == null){
                if(!ValueIteratorProviderCollectorVisitor.getValueIteratorProviders(obj.getSelect()).isEmpty()){
                    handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0067"),obj); //$NON-NLS-1$
                }
            }
            
            if (obj.getInto() != null) {
                validateSelectInto(obj);
            }                        
        }
    }
	
	public void visit(Select obj) {
        validateSelectElements(obj);
        if(obj.isDistinct()) {
            validateSortable(obj.getProjectedSymbols());
        }
    }

	public void visit(SubquerySetCriteria obj) {
		validateSubquery(obj);
		if (isNonComparable(obj.getExpression())) {
			handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0027", obj),obj); //$NON-NLS-1$
    	}
        this.validateRowLimitFunctionNotInInvalidCriteria(obj);
        
		Collection<Expression> projSymbols = obj.getCommand().getProjectedSymbols();

		//Subcommand should have one projected symbol (query with one expression
		//in SELECT or stored procedure execution that returns a single value).
		if(projSymbols.size() != 1) {
			handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0011"),obj); //$NON-NLS-1$
		}
	}
	
	@Override
	public void visit(XMLSerialize obj) {
		if (obj.getEncoding() != null ) {
        	try {
				Charset.forName(obj.getEncoding());
        	} catch (IllegalArgumentException e) {
        		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_encoding", obj.getEncoding()), obj); //$NON-NLS-1$
        	}
			if ((obj.getType() != DataTypeManager.DefaultDataClasses.BLOB && obj.getType() != DataTypeManager.DefaultDataClasses.VARBINARY)) {
				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.encoding_for_binary"), obj); //$NON-NLS-1$
			}
		}
	}

    public void visit(DependentSetCriteria obj) {
        this.validateRowLimitFunctionNotInInvalidCriteria(obj);
    }

    public void visit(SetQuery obj) {
        validateHasProjectedSymbols(obj);
        validateSetQuery(obj);
    }
    
    public void visit(Update obj) {
        validateNoXMLUpdates(obj);
        validateGroupSupportsUpdate(obj.getGroup());
        validateUpdate(obj);
    }

    public void visit(Into obj) {
        GroupSymbol target = obj.getGroup();
        validateGroupSupportsUpdate(target);
        validateMultisourceInsert(obj.getGroup());
    }

	private void validateMultisourceInsert(GroupSymbol group) {
		try {
			if (getMetadata().isMultiSource(getMetadata().getModelID(group.getMetadataID()))) {
				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.multisource_insert", group), group); //$NON-NLS-1$
			}
        } catch (QueryMetadataException e) {
			handleException(e);
		} catch (TeiidComponentException e) {
			handleException(e);
		}
	}

    public void visit(Function obj) {
    	if(FunctionLibrary.LOOKUP.equalsIgnoreCase(obj.getName())) {
    		try {
				ResolverUtil.ResolvedLookup resolvedLookup = ResolverUtil.resolveLookup(obj, getMetadata());
				if(ValidationVisitor.isNonComparable(resolvedLookup.getKeyElement())) {
		            handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_lookup_key", resolvedLookup.getKeyElement()), resolvedLookup.getKeyElement()); //$NON-NLS-1$            
		        }
			} catch (TeiidComponentException e) {
				handleException(e, obj);
			} catch (TeiidProcessingException e) {
				handleException(e, obj);
			}
        } else if (obj.getName().equalsIgnoreCase(FunctionLibrary.CONTEXT)) {
            if(!isXML) {
                // can't use this pseudo-function in non-XML queries
                handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.The_context_function_cannot_be_used_in_a_non-XML_command"), obj); //$NON-NLS-1$
            } else {
                if (!(obj.getArg(0) instanceof ElementSymbol)){
                    handleValidationError(QueryPlugin.Util.getString("ERR.015.004.0036"), obj);  //$NON-NLS-1$
                }
                
                for (Iterator<Function> functions = FunctionCollectorVisitor.getFunctions(obj.getArg(1), false).iterator(); functions.hasNext();) {
                    Function function = functions.next();
                    
                    if (function.getName().equalsIgnoreCase(FunctionLibrary.CONTEXT)) {
                        handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.Context_function_nested"), obj); //$NON-NLS-1$
                    }
                }
            }
    	} else if (obj.getName().equalsIgnoreCase(FunctionLibrary.ROWLIMIT) ||
                   obj.getName().equalsIgnoreCase(FunctionLibrary.ROWLIMITEXCEPTION)) {
            if(isXML) {
                if (!(obj.getArg(0) instanceof ElementSymbol)) {
                    // Arg must be an element symbol
                    handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.2"), obj); //$NON-NLS-1$
                }
            } else {
                // can't use this pseudo-function in non-XML queries
                handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.The_rowlimit_function_cannot_be_used_in_a_non-XML_command"), obj); //$NON-NLS-1$
            }
        } else if(obj.getName().equalsIgnoreCase(SourceSystemFunctions.XPATHVALUE)) {
	        // Validate the xpath value is valid
	        if(obj.getArgs()[1] instanceof Constant) {
	            Constant xpathConst = (Constant) obj.getArgs()[1];
                try {
                    XMLSystemFunctions.validateXpath((String)xpathConst.getValue());
                } catch(XPathException e) {
                	handleValidationError(QueryPlugin.Util.getString("QueryResolver.invalid_xpath", e.getMessage()), obj); //$NON-NLS-1$
                }
	        }
        } else if(obj.getName().equalsIgnoreCase(SourceSystemFunctions.TO_BYTES) || obj.getName().equalsIgnoreCase(SourceSystemFunctions.TO_CHARS)) {
        	try {
        		FunctionMethods.getCharset((String)((Constant)obj.getArg(1)).getValue());
        	} catch (IllegalArgumentException e) {
        		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_encoding", obj.getArg(1)), obj); //$NON-NLS-1$
        	}
        } else if (obj.isAggregate()) {
        	handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.user_defined_aggregate_as_function", obj, obj.getName()), obj); //$NON-NLS-1$
        } else if (FunctionLibrary.JSONARRAY.equalsIgnoreCase(obj.getName())) {
        	Expression[] args = obj.getArgs();
        	for (Expression expression : args) {
        		validateJSONValue(obj, expression);
			}
        }
    }

    // ############### Visitor methods for stored procedure lang objects ##################

    @Override
    public void visit(StoredProcedure obj) {
		for (SPParameter param : obj.getInputParameters()) {
			try {
                if (!getMetadata().elementSupports(param.getMetadataID(), SupportConstants.Element.NULL) && EvaluatableVisitor.isFullyEvaluatable(param.getExpression(), true)) {
	                try {
	                    // If nextValue is an expression, evaluate it before checking for null
	                    Object evaluatedValue = Evaluator.evaluate(param.getExpression());
	                    if(evaluatedValue == null) {
	                        handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0055", param.getParameterSymbol()), param.getParameterSymbol()); //$NON-NLS-1$
	                    } else if (evaluatedValue instanceof ArrayImpl && getMetadata().isVariadic(param.getMetadataID())) {
	            			ArrayImpl av = (ArrayImpl)evaluatedValue;
	            			for (Object o : av.getValues()) {
	            				if (o == null) {
	            					handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0055", param.getParameterSymbol()), param.getParameterSymbol()); //$NON-NLS-1$
	            				}
	            			}
	            		}
	                } catch(ExpressionEvaluationException e) {
	                    //ignore for now, we don't have the context which could be the problem
	                }
	            }
            } catch (TeiidComponentException e) {
            	handleException(e);
            }
		}
    }

    @Override
    public void visit(ScalarSubquery obj) {
    	validateSubquery(obj);
        Collection<Expression> projSymbols = obj.getCommand().getProjectedSymbols();

        //Scalar subquery should have one projected symbol (query with one expression
        //in SELECT or stored procedure execution that returns a single value).
        if(projSymbols.size() != 1) {
        	handleValidationError(QueryPlugin.Util.getString("ERR.015.008.0032", obj.getCommand()), obj.getCommand()); //$NON-NLS-1$
        }
    }

    public void visit(CreateProcedureCommand obj) {
        //check that the procedure does not contain references to itself
    	if (obj.getUpdateType() == Command.TYPE_UNKNOWN) {
	        if (GroupCollectorVisitor.getGroups(obj,true).contains(obj.getVirtualGroup())) {
	        	handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.Procedure_has_group_self_reference"),obj); //$NON-NLS-1$
	        }
	        if (obj.getResultSetColumns() != null) {
	        	//some unit tests bypass setting the columns
		        this.createProc = obj;
	        }
    	}
    }

    public void visit(CompoundCriteria obj) {
        // Validate use of 'rowlimit' or 'rowlimitexception' pseudo-function - each occurrence must be in a single
        // CompareCriteria which is entirely it's own conjunct (not OR'ed with anything else)
        if (isXML) {
            // Collect all occurrances of rowlimit and rowlimitexception functions
            List<Function> rowLimitFunctions = new ArrayList<Function>();
            FunctionCollectorVisitor visitor = new FunctionCollectorVisitor(rowLimitFunctions, FunctionLibrary.ROWLIMIT);
            PreOrderNavigator.doVisit(obj, visitor); 
            visitor = new FunctionCollectorVisitor(rowLimitFunctions, FunctionLibrary.ROWLIMITEXCEPTION);
            PreOrderNavigator.doVisit(obj, visitor);
            final int functionCount = rowLimitFunctions.size();
            if (functionCount > 0) {
                
                // Verify each use of rowlimit function is in a compare criteria that is 
                // entirely it's own conjunct
                Iterator<Criteria> conjunctIter = Criteria.separateCriteriaByAnd(obj).iterator();            
                
                int i = 0;
                while (conjunctIter.hasNext() && i<functionCount ) {
                    Object conjunct = conjunctIter.next();
                    if (conjunct instanceof CompareCriteria) {
                        CompareCriteria crit = (CompareCriteria)conjunct;
                        if ((rowLimitFunctions.contains(crit.getLeftExpression()) && !rowLimitFunctions.contains(crit.getRightExpression())) || 
                            (rowLimitFunctions.contains(crit.getRightExpression()) && !rowLimitFunctions.contains(crit.getLeftExpression()))) {
                        	i++;
                        }
                    }
                }
                if (i<functionCount) {
                    handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.3"), obj); //$NON-NLS-1$
                }
            }
        }
        
    }

    // ######################### Validation methods #########################

    protected void validateSelectElements(Select obj) {
    	if(isXML) {
    		return;
    	}

        Collection<ElementSymbol> elements = ElementCollectorVisitor.getElements(obj, true);
        
        Collection<ElementSymbol> cantSelect = validateElementsSupport(
            elements,
            SupportConstants.Element.SELECT );

		if(cantSelect != null) {
            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0024", cantSelect), cantSelect); //$NON-NLS-1$
		}
    }

    protected void validateHasProjectedSymbols(Command obj) {
        if(obj.getProjectedSymbols().size() == 0) {
            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0025"), obj); //$NON-NLS-1$
        }
    }

    /**
     * Validate that no elements of type OBJECT are in a SELECT DISTINCT or
     * and ORDER BY.
     * @param symbols List of SingleElementSymbol
     */
    protected void validateSortable(List<? extends Expression> symbols) {
    	for (Expression expression : symbols) {
            validateSortable(expression);
        }
    }

	private void validateSortable(Expression symbol) {
		if (isNonComparable(symbol)) {
		    handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0026", symbol), symbol); //$NON-NLS-1$
		}
	}

    public static boolean isNonComparable(Expression symbol) {
        return DataTypeManager.isNonComparable(DataTypeManager.getDataTypeName(symbol.getType()));
    }

	/**
	 * This method can be used to validate Update commands cannot be
	 * executed against XML documents.
	 */
    protected void validateNoXMLUpdates(Command obj) {
     	if(isXMLCommand(obj)) {
            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0029"), obj); //$NON-NLS-1$
     	}
    }

	/**
	 * This method can be used to validate commands used in the stored
	 * procedure languge cannot be executed against XML documents.
	 */
    protected void validateNoXMLProcedures(Command obj) {
     	if(isXMLCommand(obj)) {
            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0030"), obj); //$NON-NLS-1$
     	}
    }

    private void validateXMLQuery(Query obj) {
        if(obj.getGroupBy() != null) {
            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0031"), obj); //$NON-NLS-1$
        }
        if(obj.getHaving() != null) {
            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0032"), obj); //$NON-NLS-1$
        }
        if(obj.getLimit() != null) {
            handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.limit_not_valid_for_xml"), obj); //$NON-NLS-1$
        }
        if (obj.getOrderBy() != null) {
        	OrderBy orderBy = obj.getOrderBy();
        	for (OrderByItem item : orderBy.getOrderByItems()) {
				if (!(item.getSymbol() instanceof ElementSymbol)) {
					handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.orderby_expression_xml"), obj); //$NON-NLS-1$
				}
			}
         }
    }
    
    protected void validateGroupSupportsUpdate(GroupSymbol groupSymbol) {
    	try {
	    	if(! getMetadata().groupSupports(groupSymbol.getMetadataID(), SupportConstants.Group.UPDATE)) {
	            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0033", SQLStringVisitor.getSQLString(groupSymbol)), groupSymbol); //$NON-NLS-1$
	        }
	    } catch (TeiidComponentException e) {
	        handleException(e, groupSymbol);
	    }
    }
    
    protected void validateSetQuery(SetQuery query) {
        // Walk through sub queries - validate each one separately and
        // also check the columns of each for comparability
        for (QueryCommand subQuery : query.getQueryCommands()) {
            if(isXMLCommand(subQuery)) {
                handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0034"), query); //$NON-NLS-1$
            }
            if (subQuery instanceof Query && ((Query)subQuery).getInto() != null) {
            	handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.union_insert"), query); //$NON-NLS-1$
            }
        }
        
        if (!query.isAll() || query.getOperation() == Operation.EXCEPT || query.getOperation() == Operation.INTERSECT) {
            validateSortable(query.getProjectedSymbols());
        }
        
        if (query.isAll() && (query.getOperation() == Operation.EXCEPT || query.getOperation() == Operation.INTERSECT)) {
            handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.excpet_intersect_all"), query); //$NON-NLS-1$
        }
    }

    private void validateAggregates(Query query) {
        Select select = query.getSelect();
        GroupBy groupBy = query.getGroupBy();
        Criteria having = query.getHaving();
        validateNoAggsInClause(groupBy);
        List<GroupSymbol> correlationGroups = null;
        validateNoAggsInClause(query.getCriteria());
        if (query.getFrom() == null) {
        	validateNoAggsInClause(select);
        	validateNoAggsInClause(query.getOrderBy());
        } else {
        	validateNoAggsInClause(query.getFrom());
        	correlationGroups = query.getFrom().getGroups();
        }
        
        Set<Expression> groupSymbols = null;
        boolean hasAgg = false;
        if (groupBy != null) {
            groupSymbols = new HashSet<Expression>(groupBy.getSymbols());
            hasAgg = true;
        }
        LinkedHashSet<Expression> invalid = new LinkedHashSet<Expression>();
        LinkedHashSet<Expression> invalidWindowFunctions = new LinkedHashSet<Expression>();
        LinkedList<AggregateSymbol> aggs = new LinkedList<AggregateSymbol>();
        if (having != null) {
            validateCorrelatedReferences(query, correlationGroups, groupSymbols, having, invalid);
        	AggregateSymbolCollectorVisitor.getAggregates(having, aggs, invalid, null, invalidWindowFunctions, groupSymbols);
        	hasAgg = true;
        }
        for (Expression symbol : select.getProjectedSymbols()) {
        	if (hasAgg || !aggs.isEmpty()) {
        		validateCorrelatedReferences(query, correlationGroups, groupSymbols, symbol, invalid);
        	}
        	AggregateSymbolCollectorVisitor.getAggregates(symbol, aggs, invalid, null, null, groupSymbols);                                            
        }
        if ((!aggs.isEmpty() || hasAgg) && !invalid.isEmpty()) {
    		handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0037", invalid), invalid); //$NON-NLS-1$
        }
        if (!invalidWindowFunctions.isEmpty()) {
        	handleValidationError(QueryPlugin.Util.getString("SQLParser.window_only_top_level", invalidWindowFunctions), invalidWindowFunctions); //$NON-NLS-1$
        }
    }

    /**
     * This validation is more convoluted than needed since it is being run before rewrite/planning.
     * Ideally we would already have correlated references set on the subqueries.
     */
	private void validateCorrelatedReferences(Query query,
			final List<GroupSymbol> correlationGroups, final Set<Expression> groupingSymbols, LanguageObject object, LinkedHashSet<Expression> invalid) {
		if (query.getFrom() == null) {
			return;
		}
		ElementCollectorVisitor ecv = new ElementCollectorVisitor(invalid) {
			public void visit(ElementSymbol obj) {
				if (obj.isExternalReference() && correlationGroups.contains(obj.getGroupSymbol())
						 && (groupingSymbols == null || !groupingSymbols.contains(obj))) {
					super.visit(obj);
				}
			}
		};
		AggregateStopNavigator asn = new AggregateStopNavigator(ecv);
		object.acceptVisitor(asn);
	}

	private void validateNoAggsInClause(LanguageObject clause) {
		if (clause == null) {
        	return;
        }
		LinkedHashSet<Expression> aggs = new LinkedHashSet<Expression>();
		AggregateSymbolCollectorVisitor.getAggregates(clause, aggs, null, null, aggs, null);
		if (!aggs.isEmpty()) {
			handleValidationError(QueryPlugin.Util.getString("SQLParser.Aggregate_only_top_level", aggs), aggs); //$NON-NLS-1$
		}
	}
    
    protected void validateInsert(Insert obj) {
        Collection<ElementSymbol> vars = obj.getVariables();
        Iterator<ElementSymbol> varIter = vars.iterator();
        Collection values = obj.getValues();
        Iterator valIter = values.iterator();
        GroupSymbol insertGroup = obj.getGroup();
        try {
            boolean multiSource = getMetadata().isMultiSource(getMetadata().getModelID(insertGroup.getMetadataID()));
            // Validate that all elements in variable list are updatable
        	for (ElementSymbol insertElem : vars) {
                if(! getMetadata().elementSupports(insertElem.getMetadataID(), SupportConstants.Element.UPDATE)) {
                    handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0052", insertElem), insertElem); //$NON-NLS-1$
                }
                if (multiSource && getMetadata().isMultiSourceElement(insertElem.getMetadataID())) {
                	multiSource = false;
                }
            }
        	if (multiSource) {
        		validateMultisourceInsert(insertGroup);
        	}

            // Get elements in the group.
    		Collection<ElementSymbol> insertElmnts = new LinkedList<ElementSymbol>(ResolverUtil.resolveElementsInGroup(insertGroup, getMetadata()));

    		// remove all elements specified in insert to get the ignored elements
    		insertElmnts.removeAll(vars);

    		for (ElementSymbol nextElmnt : insertElmnts) {
				if(!getMetadata().elementSupports(nextElmnt.getMetadataID(), SupportConstants.Element.DEFAULT_VALUE) &&
					!getMetadata().elementSupports(nextElmnt.getMetadataID(), SupportConstants.Element.NULL) &&
                    !getMetadata().elementSupports(nextElmnt.getMetadataID(), SupportConstants.Element.AUTO_INCREMENT)) {
		                handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0053", new Object[] {insertGroup, nextElmnt}), nextElmnt); //$NON-NLS-1$
				}
			}

            //check to see if the elements support nulls in metadata,
            // if any of the value present in the insert are null
            while(valIter.hasNext() && varIter.hasNext()) {
                Expression nextValue = (Expression) valIter.next();
                ElementSymbol nextVar = varIter.next();

                if (EvaluatableVisitor.isFullyEvaluatable(nextValue, true)) {
                    try {
                        // If nextValue is an expression, evaluate it before checking for null
                        Object evaluatedValue = Evaluator.evaluate(nextValue);
                        if(evaluatedValue == null && ! getMetadata().elementSupports(nextVar.getMetadataID(), SupportConstants.Element.NULL)) {
                            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0055", nextVar), nextVar); //$NON-NLS-1$
                        }
                    } catch(ExpressionEvaluationException e) {
                        //ignore for now, we don't have the context which could be the problem
                    }
                }
            }// end of while
        } catch(TeiidComponentException e) {
            handleException(e, obj);
        } 
    }
    
    protected void validateSetClauseList(SetClauseList list) {
    	Set<ElementSymbol> dups = new HashSet<ElementSymbol>();
	    HashSet<ElementSymbol> changeVars = new HashSet<ElementSymbol>();
	    for (SetClause clause : list.getClauses()) {
	    	ElementSymbol elementID = clause.getSymbol();
	        if (!changeVars.add(elementID)) {
	        	dups.add(elementID);
	        }
		}
	    if(!dups.isEmpty()) {
            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0062", dups), dups); //$NON-NLS-1$
	    }
    }
    
    protected void validateUpdate(Update update) {
        try {
            UpdateInfo info = update.getUpdateInfo();

            // list of elements that are being updated
		    for (SetClause entry : update.getChangeList().getClauses()) {
        	    ElementSymbol elementID = entry.getSymbol();

                // Check that left side element is updatable
                if(! getMetadata().elementSupports(elementID.getMetadataID(), SupportConstants.Element.UPDATE)) {
                    handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0059", elementID), elementID); //$NON-NLS-1$
                }
                
                Object metadataID = elementID.getMetadataID();
                if (getMetadata().isMultiSourceElement(metadataID)){
                	handleValidationError(QueryPlugin.Util.getString("multi_source_update_not_allowed", elementID), elementID); //$NON-NLS-1$
                }

			    // Check that right expression is a constant and is non-null
                Expression value = entry.getValue();
                
                if (EvaluatableVisitor.isFullyEvaluatable(value, true)) {
                    try {
                        value = new Constant(Evaluator.evaluate(value));
                    } catch (ExpressionEvaluationException err) {
                    }
                }
                
                if(value instanceof Constant) {
    			    // If value is null, check that element supports this as a nullable column
                    if(((Constant)value).isNull() && ! getMetadata().elementSupports(elementID.getMetadataID(), SupportConstants.Element.NULL)) {
                        handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0060", SQLStringVisitor.getSQLString(elementID)), elementID); //$NON-NLS-1$
                    }// end of if
                } 
		    }
            if (info != null && info.isInherentUpdate()) {
            	validateUpdate(update, Command.TYPE_UPDATE, info);
            	Set<ElementSymbol> updateCols = update.getChangeList().getClauseMap().keySet();
            	if (!info.hasValidUpdateMapping(updateCols)) {
            		handleValidationError(QueryPlugin.Util.gs(QueryPlugin.Event.TEIID30376, updateCols), update);
            	}
            }
        } catch(TeiidException e) {
            handleException(e, update);
        }
        
        validateSetClauseList(update.getChangeList());
    }

	private void validateUpdate(TargetedCommand update, int type, UpdateInfo info) {
		String error = ProcedureContainerResolver.validateUpdateInfo(update.getGroup(), type, info);
		if (error != null) {
			handleValidationError(error, update.getGroup());
		}
	}
    
    /**
     * Validates SELECT INTO queries.
     * @param query
     * @since 4.2
     */
    protected void validateSelectInto(Query query) {
        List<Expression> symbols = query.getSelect().getProjectedSymbols();
        GroupSymbol intoGroup = query.getInto().getGroup();
        validateInto(query, symbols, intoGroup);
    }

    private void validateInto(LanguageObject query,
                                List<Expression> symbols,
                                GroupSymbol intoGroup) {
        try {
            List elementIDs = getMetadata().getElementIDsInGroupID(intoGroup.getMetadataID());
            
            // Check if there are too many elements in the SELECT clause
            if (symbols.size() != elementIDs.size()) {
                handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.select_into_wrong_elements", new Object[] {new Integer(elementIDs.size()), new Integer(symbols.size())}), query); //$NON-NLS-1$
                return;
            }
            
            for (int symbolNum = 0; symbolNum < symbols.size(); symbolNum++) {
                Expression symbol = symbols.get(symbolNum);
                Object elementID = elementIDs.get(symbolNum);
                // Check if supports updates
                if (!getMetadata().elementSupports(elementID, SupportConstants.Element.UPDATE)) {
                    handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.element_updates_not_allowed", getMetadata().getFullName(elementID)), intoGroup); //$NON-NLS-1$
                }

                Class<?> symbolType = symbol.getType();
                String symbolTypeName = DataTypeManager.getDataTypeName(symbolType);
                String targetTypeName = getMetadata().getElementType(elementID);
                if (symbolTypeName.equals(targetTypeName)) {
                    continue;
                }
                if (!DataTypeManager.isImplicitConversion(symbolTypeName, targetTypeName)) { // If there's no implicit conversion between the two
                    Object[] params = new Object [] {symbolTypeName, targetTypeName, new Integer(symbolNum + 1), query};
                    handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.select_into_no_implicit_conversion", params), query); //$NON-NLS-1$
                    continue;
                }
            }
        } catch (TeiidComponentException e) {
            handleException(e, query);
        } 
    }
    
    private void validateRowLimitFunctionNotInInvalidCriteria(Criteria obj) {
        // Collect all occurrances of rowlimit and rowlimitexception functions
        List<Function> rowLimitFunctions = new ArrayList<Function>();
        FunctionCollectorVisitor visitor = new FunctionCollectorVisitor(rowLimitFunctions, FunctionLibrary.ROWLIMIT);
        PreOrderNavigator.doVisit(obj, visitor);      
        visitor = new FunctionCollectorVisitor(rowLimitFunctions, FunctionLibrary.ROWLIMITEXCEPTION);
        PreOrderNavigator.doVisit(obj, visitor); 
        if (rowLimitFunctions.size() > 0) {
            handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.3"), obj); //$NON-NLS-1$
        }
    }
    
    /** 
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.BetweenCriteria)
     * @since 4.3
     */
    public void visit(BetweenCriteria obj) {
    	if (isNonComparable(obj.getExpression())) {
    		handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0027", obj),obj);    		 //$NON-NLS-1$
    	}
        this.validateRowLimitFunctionNotInInvalidCriteria(obj);
    }

    /** 
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.IsNullCriteria)
     * @since 4.3
     */
    public void visit(IsNullCriteria obj) {
        this.validateRowLimitFunctionNotInInvalidCriteria(obj);
    }

    /** 
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.MatchCriteria)
     * @since 4.3
     */
    public void visit(MatchCriteria obj) {
        this.validateRowLimitFunctionNotInInvalidCriteria(obj);
    }

    /** 
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.NotCriteria)
     * @since 4.3
     */
    public void visit(NotCriteria obj) {
        this.validateRowLimitFunctionNotInInvalidCriteria(obj);
    }

    /** 
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.SetCriteria)
     * @since 4.3
     */
    public void visit(SetCriteria obj) {
    	if (isNonComparable(obj.getExpression())) {
    		handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0027", obj),obj);    		 //$NON-NLS-1$
    	}
        this.validateRowLimitFunctionNotInInvalidCriteria(obj);
    }

    /** 
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.SubqueryCompareCriteria)
     * @since 4.3
     */
    public void visit(SubqueryCompareCriteria obj) {
    	validateSubquery(obj);
    	if (isNonComparable(obj.getLeftExpression())) {
    		handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0027", obj),obj);    		 //$NON-NLS-1$
    	}
        this.validateRowLimitFunctionNotInInvalidCriteria(obj);
    }
    
    public void visit(Option obj) {
        List<String> dep = obj.getDependentGroups();
        List<String> notDep = obj.getNotDependentGroups();
        if (dep != null && !dep.isEmpty()
            && notDep != null && !notDep.isEmpty()) {
            String groupName = null;
            String notDepGroup = null;
            for (Iterator<String> i = dep.iterator(); i.hasNext();) {
                groupName = i.next();
                for (Iterator<String> j = notDep.iterator(); j.hasNext();) {
                    notDepGroup = j.next();
                    if (notDepGroup.equalsIgnoreCase(groupName)) {
                        handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.group_in_both_dep", groupName), obj); //$NON-NLS-1$
                        return;
                    }
                }
            }
        }
    }
    
    /** 
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.DynamicCommand)
     */
    public void visit(DynamicCommand obj) {
        if (obj.getIntoGroup() != null) {
            validateInto(obj, obj.getAsColumns(), obj.getIntoGroup());
        }
        if (obj.getUsing() != null) {
        	validateSetClauseList(obj.getUsing());
        }
    }
    
    @Override
    public void visit(Create obj) {
    	if (!obj.getPrimaryKey().isEmpty()) {
    		validateSortable(obj.getPrimaryKey());
    	}
    	if (obj.getTableMetadata() != null) {
    		Table t = obj.getTableMetadata();
    		if (!t.getForeignKeys().isEmpty()) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.temp_fk", obj.getTable()), obj); //$NON-NLS-1$
    		}
    	}
    }
    
    /** 
     * @see org.teiid.query.sql.LanguageVisitor#visit(org.teiid.query.sql.lang.Drop)
     */
    public void visit(Drop drop) {
        if (!drop.getTable().isTempTable()) {
            handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.drop_of_nontemptable", drop.getTable()), drop); //$NON-NLS-1$
        }
    }
    
    @Override
    public void visit(CompareCriteria obj) {
    	if (isNonComparable(obj.getLeftExpression())) {
    		handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0027", obj),obj);    		 //$NON-NLS-1$
    	}
    	
        // Validate use of 'rowlimit' and 'rowlimitexception' pseudo-functions - they cannot be nested within another
        // function, and their operands must be a nonnegative integers

        // Collect all occurrences of rowlimit function
        List rowLimitFunctions = new ArrayList();
        FunctionCollectorVisitor visitor = new FunctionCollectorVisitor(rowLimitFunctions, FunctionLibrary.ROWLIMIT);
        PreOrderNavigator.doVisit(obj, visitor);   
        visitor = new FunctionCollectorVisitor(rowLimitFunctions, FunctionLibrary.ROWLIMITEXCEPTION);
        PreOrderNavigator.doVisit(obj, visitor);            
        final int functionCount = rowLimitFunctions.size();
        if (functionCount > 0) {
            Function function = null;
            Expression expr = null;
            if (obj.getLeftExpression() instanceof Function) {
                Function leftExpr = (Function)obj.getLeftExpression();
                if (leftExpr.getName().equalsIgnoreCase(FunctionLibrary.ROWLIMIT) ||
                    leftExpr.getName().equalsIgnoreCase(FunctionLibrary.ROWLIMITEXCEPTION)) {
                    function = leftExpr;
                    expr = obj.getRightExpression();
                }
            } 
            if (function == null && obj.getRightExpression() instanceof Function) {
                Function rightExpr = (Function)obj.getRightExpression();
                if (rightExpr.getName().equalsIgnoreCase(FunctionLibrary.ROWLIMIT) ||
                    rightExpr.getName().equalsIgnoreCase(FunctionLibrary.ROWLIMITEXCEPTION)) {
                    function = rightExpr;
                    expr = obj.getLeftExpression();
                }
            }
            if (function == null) {
                // must be nested, which is invalid
                handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.0"), obj); //$NON-NLS-1$
            } else {
                if (expr instanceof Constant) {
                    Constant constant = (Constant)expr;
                    if (constant.getValue() instanceof Integer) {
                        Integer integer = (Integer)constant.getValue();
                        if (integer.intValue() < 0) {
                            handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.1"), obj); //$NON-NLS-1$
                        }
                    } else {
                        handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.1"), obj); //$NON-NLS-1$
                    }
                } else if (expr instanceof Reference) {
                	((Reference)expr).setConstraint(new PositiveIntegerConstraint("ValidationVisitor.1")); //$NON-NLS-1$
                } else {
                    handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.1"), obj); //$NON-NLS-1$
                }
            }                 
        }
    }
    
    public void visit(Limit obj) {
        Expression offsetExpr = obj.getOffset();
        if (offsetExpr instanceof Constant) {
            Integer offset = (Integer)((Constant)offsetExpr).getValue();
            if (offset.intValue() < 0) {
                handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.badoffset2"), obj); //$NON-NLS-1$
            }
        } else if (offsetExpr instanceof Reference) {
        	((Reference)offsetExpr).setConstraint(new PositiveIntegerConstraint("ValidationVisitor.badoffset2")); //$NON-NLS-1$
        }
        Expression limitExpr = obj.getRowLimit();
        if (limitExpr instanceof Constant) {
            Integer limit = (Integer)((Constant)limitExpr).getValue();
            if (limit.intValue() < 0) {
                handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.badlimit2"), obj); //$NON-NLS-1$
            }
        } else if (limitExpr instanceof Reference) {
        	((Reference)limitExpr).setConstraint(new PositiveIntegerConstraint("ValidationVisitor.badlimit2")); //$NON-NLS-1$
        }
    }
    
    @Override
    public void visit(XMLForest obj) {
    	validateDerivedColumnNames(obj, obj.getArgs());
    	for (DerivedColumn dc : obj.getArgs()) {
			if (dc.getAlias() == null) {
				continue;
			}
			validateQName(obj, dc.getAlias());
			validateXMLContentTypes(dc.getExpression(), obj);
		}
    }
    
    public void visit(JSONObject obj) {
    	for (DerivedColumn dc : obj.getArgs()) {
    		validateJSONValue(obj, dc.getExpression());
		}
    }
    
    @Override
    public void visit(WindowFunction windowFunction) {
    	AggregateSymbol.Type type = windowFunction.getFunction().getAggregateFunction();
    	switch (type) {
    	case RANK:
    	case DENSE_RANK:
    	case ROW_NUMBER:
    		if (windowFunction.getWindowSpecification().getOrderBy() == null) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.ranking_requires_order_by", windowFunction), windowFunction); //$NON-NLS-1$
    		}
    		break;
    	case TEXTAGG:
    	case ARRAY_AGG:
    	case JSONARRAY_AGG:
    	case XMLAGG:
    		if (windowFunction.getWindowSpecification().getOrderBy() != null) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.window_order_by", windowFunction), windowFunction); //$NON-NLS-1$
            }
    		break;
    	}
    	validateNoSubqueriesOrOuterReferences(windowFunction);
        if (windowFunction.getFunction().getOrderBy() != null || (windowFunction.getFunction().isDistinct() && windowFunction.getWindowSpecification().getOrderBy() != null)) {
        	handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0042", new Object[] {windowFunction.getFunction(), windowFunction}), windowFunction); //$NON-NLS-1$
        }
        if (windowFunction.getWindowSpecification().getPartition() != null) {
        	validateSortable(windowFunction.getWindowSpecification().getPartition());
        }
    }
    
    @Override
    public void visit(AggregateSymbol obj) {
    	if (!inQuery) {
    		handleValidationError(QueryPlugin.Util.getString("SQLParser.Aggregate_only_top_level", obj), obj); //$NON-NLS-1$
    		return;
    	}
    	if (obj.getAggregateFunction() == AggregateSymbol.Type.USER_DEFINED) {
    		AggregateAttributes aa = obj.getFunctionDescriptor().getMethod().getAggregateAttributes();
    		if (!aa.allowsDistinct() && obj.isDistinct()) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.uda_not_allowed", "DISTINCT", obj), obj); //$NON-NLS-1$ //$NON-NLS-2$
    		}
    		if (!aa.allowsOrderBy() && obj.getOrderBy() != null) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.uda_not_allowed", "ORDER BY", obj), obj); //$NON-NLS-1$ //$NON-NLS-2$
    		}
    		if (aa.isAnalytic() && !obj.isWindowed()) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.uda_analytic", obj), obj); //$NON-NLS-1$  
    		}
    	}
    	if (obj.getCondition() != null) {
    		Expression condition = obj.getCondition();
    		validateNoSubqueriesOrOuterReferences(condition);
    	}
        Expression[] aggExps = obj.getArgs();
        
        for (Expression expression : aggExps) {
            validateNoNestedAggs(expression);
		}
        validateNoNestedAggs(obj.getOrderBy());
        validateNoNestedAggs(obj.getCondition());
        
        // Verify data type of aggregate expression
        Type aggregateFunction = obj.getAggregateFunction();
        if((aggregateFunction == Type.SUM || aggregateFunction == Type.AVG) && obj.getType() == null) {
            handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0041", new Object[] {aggregateFunction, obj}), obj); //$NON-NLS-1$
        } else if (obj.getType() != DataTypeManager.DefaultDataClasses.NULL) {
        	if (aggregateFunction == Type.XMLAGG && aggExps[0].getType() != DataTypeManager.DefaultDataClasses.XML) {
        		handleValidationError(QueryPlugin.Util.getString("AggregateValidationVisitor.non_xml", new Object[] {aggregateFunction, obj}), obj); //$NON-NLS-1$
        	} else if (obj.isBoolean() && aggExps[0].getType() != DataTypeManager.DefaultDataClasses.BOOLEAN) {
        		handleValidationError(QueryPlugin.Util.getString("AggregateValidationVisitor.non_boolean", new Object[] {aggregateFunction, obj}), obj); //$NON-NLS-1$
        	} else if (aggregateFunction == Type.JSONARRAY_AGG) {
				validateJSONValue(obj, aggExps[0]);
        	}
        }
        if((obj.isDistinct() || aggregateFunction == Type.MIN || aggregateFunction == Type.MAX) && DataTypeManager.isNonComparable(DataTypeManager.getDataTypeName(aggExps[0].getType()))) {
    		handleValidationError(QueryPlugin.Util.getString("AggregateValidationVisitor.non_comparable", new Object[] {aggregateFunction, obj}), obj); //$NON-NLS-1$
        }
        if(obj.isEnhancedNumeric()) {
        	if (!Number.class.isAssignableFrom(aggExps[0].getType())) {
        		handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0041", new Object[] {aggregateFunction, obj}), obj); //$NON-NLS-1$
        	}
        	if (obj.isDistinct()) {
        		handleValidationError(QueryPlugin.Util.getString("AggregateValidationVisitor.invalid_distinct", new Object[] {aggregateFunction, obj}), obj); //$NON-NLS-1$
        	}
        }
    	if (obj.getAggregateFunction() != Type.TEXTAGG) {
    		return;
    	}
    	TextLine tl = (TextLine)aggExps[0];
    	if (tl.isIncludeHeader()) {
    		validateDerivedColumnNames(obj, tl.getExpressions());
    	}
    	for (DerivedColumn dc : tl.getExpressions()) {
			validateXMLContentTypes(dc.getExpression(), obj);
		}
    	validateTextOptions(obj, tl.getDelimiter(), tl.getQuote());
    	if (tl.getEncoding() != null) {
    		try {
    			Charset.forName(tl.getEncoding());
    		} catch (IllegalArgumentException e) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_encoding", tl.getEncoding()), obj); //$NON-NLS-1$
    		}
    	}
    }

	private void validateJSONValue(LanguageObject obj, Expression expr) {
		if (expr.getType() != DataTypeManager.DefaultDataClasses.STRING && !DataTypeManager.isTransformable(expr.getType(), DataTypeManager.DefaultDataClasses.STRING)) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_json_value", expr, obj), obj); //$NON-NLS-1$
		}
	}

	private void validateNoSubqueriesOrOuterReferences(Expression expr) {
		if (!ValueIteratorProviderCollectorVisitor.getValueIteratorProviders(expr).isEmpty()) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.filter_subquery", expr), expr); //$NON-NLS-1$
		}
		for (ElementSymbol es : ElementCollectorVisitor.getElements(expr, false)) {
			if (es.isExternalReference()) {
				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.filter_subquery", es), es); //$NON-NLS-1$
			}
		}
	}
    
	private void validateNoNestedAggs(LanguageObject aggExp) {
		// Check for any nested aggregates (which are not allowed)
        if(aggExp != null) {
        	HashSet<Expression> nestedAggs = new LinkedHashSet<Expression>();
            AggregateSymbolCollectorVisitor.getAggregates(aggExp, nestedAggs, null, null, nestedAggs, null);
            if(!nestedAggs.isEmpty()) {
                handleValidationError(QueryPlugin.Util.getString("ERR.015.012.0039", nestedAggs), nestedAggs); //$NON-NLS-1$
            }
        }
	}
    
	private String[] validateQName(LanguageObject obj, String name) {
		try {
			return Name11Checker.getInstance().getQNameParts(name);
		} catch (QNameException e) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.xml_invalid_qname", name), obj); //$NON-NLS-1$
		}
		return null;
	}

	private void validateDerivedColumnNames(LanguageObject obj, List<DerivedColumn> cols) {
		for (DerivedColumn dc : cols) {
    		if (dc.getAlias() == null && !(dc.getExpression() instanceof ElementSymbol)) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.expression_requires_name"), obj); //$NON-NLS-1$
        	} 
		}
	}
    
    @Override
    public void visit(XMLAttributes obj) {
    	validateDerivedColumnNames(obj, obj.getArgs());
    	for (DerivedColumn dc : obj.getArgs()) {
			if (dc.getAlias() == null) {
				continue;
			}
			if ("xmlns".equals(dc.getAlias())) { //$NON-NLS-1$
				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.xml_attributes_reserved"), obj); //$NON-NLS-1$
			}
			String[] parts = validateQName(obj, dc.getAlias());
			if (parts == null) {
				continue;
			}
			if ("xmlns".equals(parts[0])) { //$NON-NLS-1$
				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.xml_attributes_reserved", dc.getAlias()), obj); //$NON-NLS-1$
			}
		}
    }
    
    @Override
    public void visit(XMLElement obj) {
    	for (Expression expression : obj.getContent()) {
    		validateXMLContentTypes(expression, obj);
    	}
    	validateQName(obj, obj.getName());
    }
    
    public void validateXMLContentTypes(Expression expression, LanguageObject parent) {
		if (expression.getType() == DataTypeManager.DefaultDataClasses.OBJECT || expression.getType() == DataTypeManager.DefaultDataClasses.BLOB) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.xml_content_type", expression), parent); //$NON-NLS-1$
		}
    }
    
    @Override
    public void visit(QueryString obj) {
    	validateDerivedColumnNames(obj, obj.getArgs());
    }
    
    @Override
    public void visit(XMLTable obj) {
    	List<DerivedColumn> passing = obj.getPassing();
    	validatePassing(obj, obj.getXQueryExpression(), passing);
    	boolean hasOrdinal = false;
    	for (XMLColumn xc : obj.getColumns()) {
			if (!xc.isOrdinal()) {
				if (xc.getDefaultExpression() != null && !EvaluatableVisitor.isFullyEvaluatable(xc.getDefaultExpression(), false)) {
					handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_default", xc.getDefaultExpression()), obj); //$NON-NLS-1$
				}
				continue;
			}
			if (hasOrdinal) {
				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.one_ordinal"), obj); //$NON-NLS-1$
				break;
			}
			hasOrdinal = true;
		}
    }
    
    @Override
    public void visit(ObjectTable obj) {
    	List<DerivedColumn> passing = obj.getPassing();
    	TreeSet<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    	for (DerivedColumn dc : passing) {
    		if (dc.getAlias() == null) {
				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.context_item_not_allowed"), obj); //$NON-NLS-1$
        	} else if (!names.add(dc.getAlias())) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.duplicate_passing", dc.getAlias()), obj); //$NON-NLS-1$
        	}
		}
    	Compilable scriptCompiler = null;
    	try {
			ScriptEngine engine = this.getMetadata().getScriptEngine(obj.getScriptingLanguage());
			obj.setScriptEngine(engine);
			if (engine instanceof Compilable) {
				scriptCompiler = (Compilable)engine;
				engine.put(ScriptEngine.FILENAME, SQLConstants.NonReserved.OBJECTTABLE);
				obj.setCompiledScript(scriptCompiler.compile(obj.getRowScript()));
			}
		} catch (TeiidProcessingException e) {
			handleValidationError(e.getMessage(), obj);
		} catch (ScriptException e) {
			handleValidationError(QueryPlugin.Util.gs(QueryPlugin.Event.TEIID31110, obj.getRowScript(), e.getMessage()), obj); //$NON-NLS
		}
    	for (ObjectColumn xc : obj.getColumns()) {
    		if (scriptCompiler != null) {
    			try {
					xc.setCompiledScript(scriptCompiler.compile(xc.getPath()));
				} catch (ScriptException e) {
					handleValidationError(QueryPlugin.Util.gs(QueryPlugin.Event.TEIID31110, xc.getPath(), e.getMessage()), obj); //$NON-NLS
				}
    		}
			if (xc.getDefaultExpression() != null && !EvaluatableVisitor.isFullyEvaluatable(xc.getDefaultExpression(), false)) {
				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_default", xc.getDefaultExpression()), obj); //$NON-NLS-1$
			}
		}
    }
    
    @Override
    public void visit(XMLQuery obj) {
    	validatePassing(obj, obj.getXQueryExpression(), obj.getPassing());
    }

	private void validatePassing(LanguageObject obj, SaxonXQueryExpression xqe, List<DerivedColumn> passing) {
		boolean context = false;
    	boolean hadError = false;
    	TreeSet<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    	for (DerivedColumn dc : passing) {
    		if (dc.getAlias() == null) {
    			Class<?> type = dc.getExpression().getType();
    			if (type != DataTypeManager.DefaultDataClasses.XML) {
    				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.context_item_type"), obj); //$NON-NLS-1$
    			}
    			if (context && !hadError) {
    				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.passing_requires_name"), obj); //$NON-NLS-1$
    				hadError = true;
    			}
    			context = true;
        	} else { 
        		validateXMLContentTypes(dc.getExpression(), obj);
        		if (!names.add(dc.getAlias())) {
        			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.duplicate_passing", dc.getAlias()), obj); //$NON-NLS-1$
        		}
        	}
		}
    	if (xqe.usesContextItem() && !context) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.context_required"), obj); //$NON-NLS-1$    		
    	}
	}
    
    @Override
    public void visit(XMLNamespaces obj) {
    	boolean hasDefault = false;
    	for (XMLNamespaces.NamespaceItem item : obj.getNamespaceItems()) {
			if (item.getPrefix() != null) {
				if (item.getPrefix().equals("xml") || item.getPrefix().equals("xmlns")) { //$NON-NLS-1$ //$NON-NLS-2$
					handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.xml_namespaces_reserved"), obj); //$NON-NLS-1$
				} else if (!Name11Checker.getInstance().isValidNCName(item.getPrefix())) {
					handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.xml_namespaces_invalid", item.getPrefix()), obj); //$NON-NLS-1$
				}
				if (item.getUri().length() == 0) {
					handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.xml_namespaces_null_uri"), obj); //$NON-NLS-1$
				}
				continue;
			}
			if (hasDefault) {
				handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.xml_namespaces"), obj); //$NON-NLS-1$
				break;
			}
			hasDefault = true;
		}
    }
    
    @Override
    public void visit(TextTable obj) {
    	boolean widthSet = false;
    	Character delimiter = null;
    	Character quote = null;
    	boolean usingSelector = false;
    	for (TextTable.TextColumn column : obj.getColumns()) {
			if (column.getWidth() != null) {
				widthSet = true;
				if (column.getWidth() < 0) {
					handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_negative"), obj); //$NON-NLS-1$
				}
			} else if (widthSet) {
    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_invalid_width"), obj); //$NON-NLS-1$
			}
			if (column.getSelector() != null) {
				usingSelector = true;
				if (obj.getSelector() != null && obj.getSelector().equals(column.getSelector())) {
					handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_selector_required"), obj); //$NON-NLS-1$
				}
			}
        	if (column.getPosition() != null && column.getPosition() < 0) {
	    		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_negative"), obj); //$NON-NLS-1$
	    	}
		}
    	if (widthSet) {
    		if (obj.getDelimiter() != null || obj.getHeader() != null || obj.getQuote() != null || obj.getSelector() != null || usingSelector) {
        		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_width"), obj); //$NON-NLS-1$
    		}
    	} else {
        	if (obj.getHeader() != null && obj.getHeader() < 0) {
	    		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_negative"), obj); //$NON-NLS-1$
	    	}
        	if (!obj.isUsingRowDelimiter()) {
        		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.fixed_option"), obj); //$NON-NLS-1$
        	}
    		delimiter = obj.getDelimiter();
    		quote = obj.getQuote();
			validateTextOptions(obj, delimiter, quote);
    	}
    	if (obj.getSkip() != null && obj.getSkip() < 0) {
    		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_negative"), obj); //$NON-NLS-1$
    	}
    	if (usingSelector && obj.getSelector() == null) {
    		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_selector_required"), obj); //$NON-NLS-1$
    	}
    }

	private void validateTextOptions(LanguageObject obj, Character delimiter,
			Character quote) {
		if (quote == null) {
			quote = '"';
		} 
		if (delimiter == null) {
			delimiter = ',';
		}
		if (EquivalenceUtil.areEqual(quote, delimiter)) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_delimiter"), obj); //$NON-NLS-1$
		}
		if (EquivalenceUtil.areEqual(quote, '\n') 
				|| EquivalenceUtil.areEqual(delimiter, '\n')) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.text_table_newline"), obj); //$NON-NLS-1$
		}
	}
    
    @Override
    public void visit(XMLParse obj) {
    	if (obj.getExpression().getType() != DataTypeManager.DefaultDataClasses.STRING && 
    			obj.getExpression().getType() != DataTypeManager.DefaultDataClasses.CLOB &&
    			obj.getExpression().getType() != DataTypeManager.DefaultDataClasses.BLOB) {
    		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.xmlparse_type"), obj); //$NON-NLS-1$
    	}
    }
    
    @Override
    public void visit(ExistsCriteria obj) {
    	validateSubquery(obj);
    }
    
    @Override
    public void visit(SubqueryFromClause obj) {
    	validateSubquery(obj);
    }
    
    @Override
    public void visit(LoopStatement obj) {
    	validateSubquery(obj);
    }
    
    @Override
    public void visit(WithQueryCommand obj) {
    	validateSubquery(obj);
    }
    
    public void visit(AlterView obj) {
    	try {
			QueryResolver.validateProjectedSymbols(obj.getTarget(), getMetadata(), obj.getDefinition());
			Validator.validate(obj.getDefinition(), getMetadata(), this);
			validateAlterTarget(obj);
		} catch (QueryValidatorException e) {
			handleValidationError(e.getMessage(), obj.getDefinition());
		} catch (TeiidComponentException e) {
			handleException(e);
		}
    }

	private void validateAlterTarget(Alter<?> obj) {
		if (getMetadata().getImportedModels().contains(obj.getTarget().getSchema())) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_alter", obj.getTarget()), obj.getTarget()); //$NON-NLS-1$
		}
	}

    @Override
    public void visit(AlterProcedure obj) {
    	GroupSymbol gs = obj.getTarget();
    	validateAlterTarget(obj);
    	try {
	    	if (!gs.isProcedure() || !getMetadata().isVirtualModel(getMetadata().getModelID(gs.getMetadataID()))) {
	    		handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.not_a_procedure", gs), gs); //$NON-NLS-1$
	    		return;
	    	}
	    	Validator.validate(obj.getDefinition(), getMetadata(), this);
	    	StoredProcedureInfo info = getMetadata().getStoredProcedureInfoForProcedure(gs.getName());
	    	for (SPParameter param : info.getParameters()) {
	    		if (param.getParameterType() == SPParameter.RESULT_SET) {
	    	    	QueryResolver.validateProjectedSymbols(gs, param.getResultSetColumns(), obj.getDefinition().getProjectedSymbols());
	    	    	break;
	    		}
	    	}
    	} catch (QueryValidatorException e) {
			handleValidationError(e.getMessage(), obj.getDefinition().getBlock());
    	} catch (TeiidComponentException e) {
			handleException(e);
		}
    }
    
    public void visit(Block obj) {
    	if (obj.getLabel() == null) {
    		return;
    	}
		for (LanguageObject lo : stack) {
			if (lo instanceof Labeled) {
				Labeled labeled = (Labeled)lo;
	    		if (obj.getLabel().equalsIgnoreCase(labeled.getLabel())) {
	    			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.duplicate_block_label", obj.getLabel()), obj); //$NON-NLS-1$
	    		}
			}
		}
    }
    
    @Override
    public void visit(CommandStatement obj) {
    	if (this.createProc == null || this.createProc.getResultSetColumns().isEmpty() || !obj.isReturnable() || !obj.getCommand().returnsResultSet()) {
    		return;
    	}
		List<? extends Expression> symbols = obj.getCommand().getResultSetColumns();
		if (symbols == null && obj.getCommand() instanceof DynamicCommand) {
			DynamicCommand cmd = (DynamicCommand)obj.getCommand();
			cmd.setAsColumns(this.createProc.getResultSetColumns());
			return;
		}
		try {
			QueryResolver.validateProjectedSymbols(createProc.getVirtualGroup(), createProc.getResultSetColumns(), symbols);
		} catch (QueryValidatorException e) {
			handleValidationError(QueryPlugin.Util.gs(QueryPlugin.Event.TEIID31121, createProc.getVirtualGroup(), obj, e.getMessage()), obj);
		}
    }
    
    @Override
    public void visit(BranchingStatement obj) {
		boolean matchedLabel = false;
		boolean inLoop = false;
		for (LanguageObject lo : stack) {
			if (lo instanceof LoopStatement || lo instanceof WhileStatement) {
				inLoop = true;
				if (obj.getLabel() == null) {
					break;
				}
				matchedLabel |= obj.getLabel().equalsIgnoreCase(((Labeled)lo).getLabel());
			} else if (obj.getLabel() != null && lo instanceof Block && obj.getLabel().equalsIgnoreCase(((Block)lo).getLabel())) {
				matchedLabel = true;
				if (obj.getMode() != BranchingMode.LEAVE) {
					handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.invalid_label", obj.getLabel()), obj); //$NON-NLS-1$
				}
			}
		}
		if (obj.getMode() != BranchingMode.LEAVE && !inLoop) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.no_loop"), obj); //$NON-NLS-1$
		}
		if (obj.getLabel() != null && !matchedLabel) {
			handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.unknown_block_label", obj.getLabel()), obj); //$NON-NLS-1$
		}
    }
    
    @Override
    public void visit(AlterTrigger obj) {
    	validateAlterTarget(obj);
    	validateGroupSupportsUpdate(obj.getTarget());
		try {
			if (obj.getDefinition() != null) {
				Validator.validate(obj.getDefinition(), getMetadata(), this);
			}			
		} catch (TeiidComponentException e) {
			handleException(e);
		}
    }

    //TODO: it may be simpler to catch this in the parser
    private void validateSubquery(SubqueryContainer<?> subQuery) {
    	if (subQuery.getCommand() instanceof Query && ((Query)subQuery.getCommand()).getInto() != null) {
        	handleValidationError(QueryPlugin.Util.getString("ValidationVisitor.subquery_insert"), subQuery.getCommand()); //$NON-NLS-1$
        }
    }
    
}
