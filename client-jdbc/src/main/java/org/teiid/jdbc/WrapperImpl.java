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

package org.teiid.jdbc;

import java.sql.SQLException;
//## JDBC4.0-begin ##
import java.sql.Wrapper;
//## JDBC4.0-end ##

import com.metamatrix.core.util.ArgCheck;

public class WrapperImpl 
	//## JDBC4.0-begin ##
	implements Wrapper 
	//## JDBC4.0-end ##	
	{
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		ArgCheck.isNotNull(iface);
		
		return iface.isInstance(this);
	}

	public <T> T unwrap(Class<T> iface) throws SQLException {
		if (!isWrapperFor(iface)) {
			throw new SQLException(JDBCPlugin.Util.getString("WrapperImpl.wrong_class", iface)); //$NON-NLS-1$
		}
		
		return iface.cast(this);
	}

}