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

package com.metamatrix.common.log;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class LogConfigurationImpl implements LogConfiguration, Serializable {

	private static final long serialVersionUID = 4626429149431532292L;
	
	Map<String, Integer> contextMap = null;
	private int defaultLevel;
	
	public LogConfigurationImpl(int defaultLevel) {
		this.defaultLevel = defaultLevel;
		this.contextMap = new HashMap<String, Integer>();
	}
	
	public LogConfigurationImpl(Map<String, Integer> contextMap) {
		this.contextMap = contextMap;
	}
	
	@Override
	public Set<String> getContexts() {
		return this.contextMap.keySet();
	}

	@Override
	public int getLogLevel(String context) {				
		Integer level = this.contextMap.get(context);
		if (level == null) {
			context = fixContext(context);	
		}
		level = this.contextMap.get(context);
		if (level != null) {
			return level;
		}
		return defaultLevel;
	}

	private String fixContext(String context) {
		if (context.indexOf('.') == -1) {
			context = ROOT_CONTEXT+context;
		}
		return context;
	}

	@Override
	public void setLogLevel(String context, int logLevel) {
		this.contextMap.put(context, logLevel);
	}

	@Override
	public boolean isEnabled(String context, int msgLevel) {
		int level = getLogLevel(context);
		return level >= msgLevel;
	}
	
	public static LogConfiguration makeCopy(LogConfiguration config) {
    	Map<String, Integer> contextMap = new HashMap<String, Integer>();
    	for(String context:config.getContexts()) {
    		contextMap.put(context, config.getLogLevel(context));
    	}
    	return new LogConfigurationImpl(contextMap);
	}
}