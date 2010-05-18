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

package org.teiid.translator.jdbc.mysql;

import org.junit.BeforeClass;
import org.junit.Test;
import org.teiid.translator.TranslatorException;
import org.teiid.translator.jdbc.TranslationHelper;

/**
 */
public class TestMySQL5Translator {

    private static MySQL5ExecutionFactory TRANSLATOR; 
    
    @BeforeClass public static void oneTimeSetup() throws TranslatorException {
        TRANSLATOR = new MySQL5ExecutionFactory();        
        TRANSLATOR.start();
    }

    @Test public void testChar() throws Exception {
    	String input = "SELECT intkey, CHR(CONVERT(bigintegervalue, integer)) FROM BQT1.MediumA"; //$NON-NLS-1$
        String output = "SELECT MediumA.IntKey, char(cast(MediumA.BigIntegerValue AS signed) USING ASCII) FROM MediumA"; //$NON-NLS-1$
          
        TranslationHelper.helpTestVisitor(TranslationHelper.BQT_VDB,
            input, 
            output, TRANSLATOR);
    }
    
}