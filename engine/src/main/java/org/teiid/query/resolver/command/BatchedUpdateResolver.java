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

package org.teiid.query.resolver.command;

import java.util.Collections;
import java.util.Iterator;

import org.teiid.api.exception.query.QueryMetadataException;
import org.teiid.api.exception.query.QueryResolverException;
import org.teiid.core.TeiidComponentException;
import org.teiid.query.analysis.AnalysisRecord;
import org.teiid.query.metadata.TempMetadataAdapter;
import org.teiid.query.resolver.CommandResolver;
import org.teiid.query.resolver.QueryResolver;
import org.teiid.query.sql.lang.BatchedUpdateCommand;
import org.teiid.query.sql.lang.Command;


/** 
 * Resolver for BatchedUpdateCommands
 * @since 4.2
 */
public class BatchedUpdateResolver implements CommandResolver {
    
    /** 
     * @see com.metamatrix.query.resolver.command.AbstractCommandResolver#resolveCommand(org.teiid.query.sql.lang.Command, boolean, org.teiid.query.analysis.AnalysisRecord, boolean)
     */
    public void resolveCommand(Command command, TempMetadataAdapter metadata, AnalysisRecord analysis, boolean resolveNullLiterals) 
        throws QueryMetadataException, QueryResolverException, TeiidComponentException {

        BatchedUpdateCommand batchedUpdateCommand = (BatchedUpdateCommand) command;
        
        for (Iterator i = batchedUpdateCommand.getSubCommands().iterator(); i.hasNext();) {
            Command subCommand = (Command)i.next();
            QueryResolver.setChildMetadata(subCommand, command);
            QueryResolver.resolveCommand(subCommand, Collections.EMPTY_MAP, metadata.getMetadata(), analysis);
        }
    }

}