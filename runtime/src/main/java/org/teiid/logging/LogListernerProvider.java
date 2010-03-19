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

package org.teiid.logging;

import org.apache.log4j.Logger;

import com.metamatrix.core.log.LogListener;

public class LogListernerProvider {
	
	public LogListener get() {
    	return new Log4jListener();
	}

	/**
	 * Log4J Listener
	 */
	static class Log4jListener implements LogListener{

		@Override
		public void log(int level, String context, Object msg) {
			Logger log4j = Log4JUtil.getLogger(context);
			log4j.log(Log4JUtil.convert2Log4JLevel(level), msg);
		}

		public void log(int level, String context, Throwable t, Object msg) {
			Logger log4j = Log4JUtil.getLogger(context);
			log4j.log(Log4JUtil.convert2Log4JLevel(level), msg, t);
		}
						
		@Override
		public void shutdown() {
		}

	}
}