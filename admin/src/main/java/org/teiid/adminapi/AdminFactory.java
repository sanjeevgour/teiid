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

package org.teiid.adminapi;

import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT_REMOVE_OPERATION;
import static org.jboss.as.controller.client.helpers.ClientConstants.DEPLOYMENT_UNDEPLOY_OPERATION;
import static org.jboss.as.controller.client.helpers.ClientConstants.RESULT;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.UnknownHostException;
import java.util.*;
import java.util.logging.Logger;

import javax.security.auth.callback.*;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.RealmChoiceCallback;

import org.jboss.as.cli.Util;
import org.jboss.as.cli.operation.OperationFormatException;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestAddress;
import org.jboss.as.cli.operation.impl.DefaultOperationRequestBuilder;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.teiid.adminapi.PropertyDefinition.RestartType;
import org.teiid.adminapi.VDB.ConnectionType;
import org.teiid.adminapi.impl.*;
import org.teiid.adminapi.impl.VDBMetadataMapper.RequestMetadataMapper;
import org.teiid.adminapi.impl.VDBMetadataMapper.SessionMetadataMapper;
import org.teiid.adminapi.impl.VDBMetadataMapper.TransactionMetadataMapper;
import org.teiid.core.util.ObjectConverterUtil;


/** 
 * Singleton factory for class for creating Admin connections to the Teiid
 */
@SuppressWarnings("nls")
public class AdminFactory {
	private static final Logger LOGGER = Logger.getLogger(AdminFactory.class.getName());
	private static Set<String> optionalProps = new HashSet<String>(Arrays.asList("user-name", "password", "check-valid-connection-sql", "pool-prefill", "max-pool-size", "min-pool-size"));
	private static AdminFactory INSTANCE = new AdminFactory();
	
	public static AdminFactory getInstance() {
		return INSTANCE;
	}
    /**
     * Creates a ServerAdmin with the specified connection properties. 
     * @param userName
     * @param password
     * @param serverURL
     * @param applicationName
     * @return
     * @throws AdminException
     */
    public Admin createAdmin(String host, int port, String userName, char[] password) throws AdminException {
        if(host == null) {
            host = "localhost"; //$NON-NLS-1$
        }

        if(port < 0) {
            port = 9999;
        }

        try {
            CallbackHandler cbh = new AuthenticationCallbackHandler(userName, password);
            ModelControllerClient newClient = ModelControllerClient.Factory.create(host, port, cbh);

            List<String> nodeTypes = Util.getNodeTypes(newClient, new DefaultOperationRequestAddress());
            if (!nodeTypes.isEmpty()) {
                boolean domainMode = nodeTypes.contains("server-group"); //$NON-NLS-1$ 
                LOGGER.info("Connected to " //$NON-NLS-1$ 
                        + (domainMode ? "domain controller at " : "standalone controller at ") //$NON-NLS-1$ //$NON-NLS-2$
                        + host + ":" + port); //$NON-NLS-1$ 
                return new AdminImpl(newClient);
            } 
            LOGGER.info(AdminPlugin.Util.gs(AdminPlugin.Event.TEIID70051, host, port)); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (UnknownHostException e) {
        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70000, AdminPlugin.Util.gs(AdminPlugin.Event.TEIID70000, host, e.getLocalizedMessage()));
        }
        return null;
    }
    
    public Admin createAdmin(ModelControllerClient connection) {
    	return new AdminImpl(connection);
    }
    
    private class AuthenticationCallbackHandler implements CallbackHandler {
        private boolean realmShown = false;
        private String userName = null;
        private char[] password = null;

        public AuthenticationCallbackHandler(String user, char[] password) {
        	this.userName = user;
        	this.password = password;
        }
        
        public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
            // Special case for anonymous authentication to avoid prompting user for their name.
            if (callbacks.length == 1 && callbacks[0] instanceof NameCallback) {
                ((NameCallback)callbacks[0]).setName("anonymous CLI user"); //$NON-NLS-1$
                return;
            }

            for (Callback current : callbacks) {
                if (current instanceof RealmCallback) {
                    RealmCallback rcb = (RealmCallback) current;
                    String defaultText = rcb.getDefaultText();
                    rcb.setText(defaultText); // For now just use the realm suggested.
                    if (realmShown == false) {
                        realmShown = true;
                    }
                } else if (current instanceof RealmChoiceCallback) {
                    throw new UnsupportedCallbackException(current, "Realm choice not currently supported."); //$NON-NLS-1$
                } else if (current instanceof NameCallback) {
                    NameCallback ncb = (NameCallback) current;
                    ncb.setName(userName);
                } else if (current instanceof PasswordCallback) {
                    PasswordCallback pcb = (PasswordCallback) current;
                    pcb.setPassword(password);
                } else {
                    throw new UnsupportedCallbackException(current);
                }
            }
        }

    }    
    
    public class AdminImpl implements Admin{
    	private static final String CLASS_NAME = "class-name";
		private static final String JAVA_CONTEXT = "java:/";
		private ModelControllerClient connection;
    	private boolean domainMode = false;
    	private String profileName;
    	
    	public AdminImpl (ModelControllerClient connection) {
    		this.connection = connection;
            List<String> nodeTypes = Util.getNodeTypes(connection, new DefaultOperationRequestAddress());
            if (!nodeTypes.isEmpty()) {
                domainMode = nodeTypes.contains("server-group"); //$NON-NLS-1$
            }     		
    	}
    	
		@Override
		public void clearCache(String cacheType) throws AdminException {
	        final ModelNode request = buildRequest("teiid", "clear-cache", "cache-type", cacheType);//$NON-NLS-1$ //$NON-NLS-2$
	        execute(request);
		}

		@Override
		public void clearCache(String cacheType, String vdbName, int vdbVersion) throws AdminException {
	        final ModelNode request = buildRequest("teiid", "clear-cache", 
	        		"cache-type", cacheType,
	        		"vdb-name", vdbName,
	        		"vdb-version", String.valueOf(vdbVersion));//$NON-NLS-1$ //$NON-NLS-2$
	        execute(request);
		}

		@Override
		public void close() {
			if (this.connection != null) {
		        try {
		        	connection.close();
		        } catch (Throwable t) {
		        	//ignore
		        }				
		        this.connection = null;
				this.domainMode = false;
			}
		}
		
		private void createConnectionFactory(String deploymentName,	String rarName, Properties properties)	throws AdminException {

			if (!getDeployedResourceAdapterNames().contains(deploymentName)) {
				///subsystem=resource-adapters/resource-adapter=fileDS:add
				addResourceAdapter(deploymentName, rarName);
			}
			
			///subsystem=resource-adapters/resource-adapter=fileDS/connection-definitions=fileDS:add(jndi-name=java\:\/fooDS)
			DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
	        final ModelNode request;

	        try {
	        	addProfileNode(builder);	        	
	            builder.addNode("subsystem", "resource-adapters"); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode("resource-adapter", deploymentName); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode("connection-definitions", deploymentName); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.setOperationName("add"); 
	            builder.addProperty("jndi-name", addJavaContext(deploymentName));
	            builder.addProperty("enabled", "true");
	            if (properties.getProperty(CLASS_NAME) != null) {
	            	builder.addProperty(CLASS_NAME, properties.getProperty(CLASS_NAME));
	            }
	            request = builder.buildRequest();
	        } catch (OperationFormatException e) {
	            throw new IllegalStateException("Failed to build operation", e); //$NON-NLS-1$
	        }
			
	        execute(request);
	        
	        // add all the config properties
            Enumeration keys = properties.propertyNames();
            while (keys.hasMoreElements()) {
            	String key = (String)keys.nextElement();
            	if (key.equals(CLASS_NAME)) {
            		continue;
            	}
            	addConfigProperty(deploymentName, key, properties.getProperty(key));
            }
            
            activateConnectionFactory(deploymentName);
		}

		// /subsystem=resource-adapters/resource-adapter=fileDS/connection-definitions=fileDS/config-properties=ParentDirectory2:add(value=/home/rareddy/testing)
		private void addConfigProperty(String deploymentName, String key, String value) throws AdminProcessingException {
			DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
	        final ModelNode request;
	        try {
	        	addProfileNode(builder);	        	
	            builder.addNode("subsystem", "resource-adapters"); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode("resource-adapter", deploymentName); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode("connection-definitions", deploymentName); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode("config-properties", key); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.setOperationName("add"); 
	            builder.addProperty("value", value);
	            request = builder.buildRequest();
	        } catch (OperationFormatException e) {
	            throw new IllegalStateException("Failed to build operation", e); //$NON-NLS-1$
	        }
			
	        execute(request);
		}
		
		// /subsystem=resource-adapters/resource-adapter=fileDS:activate
		private void activateConnectionFactory(String deploymentName) throws AdminProcessingException {
			DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
	        final ModelNode request;
	        try {
	        	addProfileNode(builder);
	            builder.addNode("subsystem", "resource-adapters"); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode("resource-adapter", deploymentName); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.setOperationName("activate"); 
	            request = builder.buildRequest();
	        } catch (OperationFormatException e) {
	            throw new IllegalStateException("Failed to build operation", e); //$NON-NLS-1$
	        }
			
	        execute(request);
		}

		private void addProfileNode(DefaultOperationRequestBuilder builder) throws AdminProcessingException {
			if (this.domainMode) {
				String profile = getProfileName();
				if (profile != null) {
					builder.addNode("profile",profile);
				}
			}
		}

		// /subsystem=resource-adapters/resource-adapter=teiid-connector-ws.rar:add(archive=teiid-connector-ws.rar, transaction-support=NoTransaction)
		private void addResourceAdapter(String deploymentName, String rarName) throws AdminProcessingException {
			DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
	        final ModelNode request;

	        try {
	        	addProfileNode(builder);	        	
	            builder.addNode("subsystem", "resource-adapters"); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode("resource-adapter", deploymentName); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.setOperationName("add"); 
	            request = builder.buildRequest();
	            request.get("archive").set(rarName);
	            request.get("transaction-support").set("NoTransaction");
	        } catch (OperationFormatException e) {
	            throw new IllegalStateException("Failed to build operation", e); //$NON-NLS-1$
	        }
			
	        execute(request);				
		}
		
		class AbstractMetadatMapper implements MetadataMapper<String>{
			@Override
			public ModelNode wrap(String obj, ModelNode node) {
				return null;
			}
			@Override
			public String unwrap(ModelNode node) {
				return null;
			}
			@Override
			public ModelNode describe(ModelNode node) {
				return null;
			}
		}		
		
		public Set<String> getInstalledJDBCDrivers() throws AdminProcessingException {
			HashSet<String> driverList = new HashSet<String>();
			driverList.addAll(getChildNodeNames("datasources", "jdbc-driver"));

			if (!this.domainMode) {
				//'installed-driver-list' not available in the domain mode.
				final ModelNode request = buildRequest("datasources", "installed-drivers-list");
		        try {
		            ModelNode outcome = this.connection.execute(request);
		            if (Util.isSuccess(outcome)) {
			            List<String> drivers = getList(outcome, new AbstractMetadatMapper() {
							@Override
							public String unwrap(ModelNode node) {
								if (node.hasDefined("driver-name")) {
									return node.get("driver-name").asString();
								}
								return null;
							}
						});
			            driverList.addAll(drivers);
		            }
		        } catch (Exception e) {
		        	throw new AdminProcessingException(AdminPlugin.Event.TEIID70052, e);
		        }	
			}
			else {
				// TODO: AS7 needs to provide better way to query the deployed JDBC drivers
				List<String> deployments = getChildNodeNames(null, "deployment");
	            for (String deployment:deployments) {
	            	if (!deployment.contains("translator") && deployment.endsWith(".jar")) {
	            		driverList.add(deployment);
	            	}
	            }				
			}
	        return driverList;
		}		
		
		public String getProfileName() throws AdminProcessingException {
			if (!this.domainMode) {
				return null;
			}
			if (this.profileName == null) {
				this.profileName = getChildNodeNames(null, "profile").get(0);
			}
			return this.profileName;
		}		
		
		@Override
		public void createDataSource(String deploymentName,	String templateName, Properties properties)	throws AdminException {
			deploymentName = removeJavaContext(deploymentName);
			
			Collection<String> dsNames = getDataSourceNames();
			if (dsNames.contains(deploymentName)) {
				 throw new AdminProcessingException(AdminPlugin.Event.TEIID70003, AdminPlugin.Util.gs(AdminPlugin.Event.TEIID70003, deploymentName));
			}
			
			Set<String> resourceAdapters = getAvailableResourceAdapterNames();
        	if (resourceAdapters.contains(templateName)) {
	            createConnectionFactory(deploymentName, templateName, properties);
	            return;
        	}
			
        	Set<String> drivers = getInstalledJDBCDrivers();
        	if (!drivers.contains(templateName)) {
        		 throw new AdminProcessingException(AdminPlugin.Event.TEIID70004, AdminPlugin.Util.gs(AdminPlugin.Event.TEIID70004, templateName));
        	}
        	
			DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
	        ModelNode request;
	        try {
	        	
	        	addProfileNode(builder);
	        	
	            builder.addNode("subsystem", "datasources"); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode("data-source", deploymentName); //$NON-NLS-1$	        		
	        	
	            builder.setOperationName("add"); 
	            
	            builder.addProperty("jndi-name", addJavaContext(deploymentName));
	            builder.addProperty("driver-name", templateName);
	            
	            builder.addProperty("pool-name", deploymentName);
	            
	            if (properties != null) {
		            builder.addProperty("connection-url", properties.getProperty("connection-url"));
		            for (String prop : optionalProps) {
		            	String value = properties.getProperty(prop);
		            	if (value != null) {
		            		builder.addProperty(prop, value);
		            	}
		            }
	            }
	            else {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70005, AdminPlugin.Util.gs(AdminPlugin.Event.TEIID70005));
	            }
	            
	            request = builder.buildRequest();
	        } catch (OperationFormatException e) {
	            throw new IllegalStateException("Failed to build operation", e); //$NON-NLS-1$
	        }
	        
	        // execute request
	        execute(request);

	        // issue the "enable" operation
			builder = new DefaultOperationRequestBuilder();
	        try {
	            builder.addNode("subsystem", "datasources"); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode("data-source", deploymentName); //$NON-NLS-1$
	            builder.setOperationName("enable"); 
	            request = builder.buildRequest();
	        } catch (OperationFormatException e) {
	            throw new IllegalStateException("Failed to build operation", e); //$NON-NLS-1$
	        }
	        
	        execute(request);
		}

		private void execute(final ModelNode request) throws AdminProcessingException {
			try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	                 throw new AdminProcessingException(AdminPlugin.Event.TEIID70006, Util.getFailureDescription(outcome));
	            }
	        } catch (IOException e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70007, e);
	        }
		}

		@Override
		public void deleteDataSource(String deployedName) throws AdminException {
			deployedName = removeJavaContext(deployedName);
			
			Collection<String> dsNames = getDataSourceNames();
			if (!dsNames.contains(deployedName)) {
				 throw new AdminProcessingException(AdminPlugin.Event.TEIID70008, AdminPlugin.Util.gs(AdminPlugin.Event.TEIID70008, deployedName));
			}
			
			boolean deleted = deleteDS(deployedName,"datasources", "data-source");
			
			// check xa connections
			if (!deleted) {
				deleted = deleteDS(deployedName,"datasources", "xa-data-source");
			}
			
			// check connection factories
			if (!deleted) {
				Map<String, String> raDSMap = getResourceAdapterDataSources();
				// deployed rar name, may be it is == deployedName or if server restarts it will be rar name or rar->[1..n] name
				String rarName = raDSMap.get(deployedName);
				if (rarName != null) {
					deleted = deleteDS(rarName,"resource-adapters", "resource-adapter", deployedName);	
				}
			}
			
			if (!deleted) {
				throw new AdminProcessingException(AdminPlugin.Event.TEIID70008, AdminPlugin.Util.gs(AdminPlugin.Event.TEIID70008, deployedName));
			}
		}

		private String removeJavaContext(String deployedName) {
			if (deployedName.startsWith(JAVA_CONTEXT)) {
				deployedName = deployedName.substring(6);
			}
			return deployedName;
		}
		
		private String addJavaContext(String deployedName) {
			if (!deployedName.startsWith(JAVA_CONTEXT)) {
				deployedName = JAVA_CONTEXT+deployedName;
			}
			return deployedName;
		}		

		private boolean deleteDS(String deployedName, String... subsystem) throws AdminProcessingException {
			DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
	        final ModelNode request;
	        
	        try {
	        	addProfileNode(builder);
	        	
	            builder.addNode("subsystem", subsystem[0]); //$NON-NLS-1$ //$NON-NLS-2$
	            builder.addNode(subsystem[1], deployedName);
	            builder.setOperationName("remove"); 
	            request = builder.buildRequest();
	        } catch (OperationFormatException e) {
	            throw new IllegalStateException("Failed to build operation", e); //$NON-NLS-1$
	        }
			
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	                return false;
	            }
	        } catch (IOException e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70009, e);
	        }
	        return true;
		}

		@Override
		public void undeploy(String deployedName) throws AdminException {
			ModelNode request;
			try {			
				request = buildUndeployRequest(deployedName, false);
	        } catch (OperationFormatException e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70010, e);
	        }
			execute(request);
		}
		
		public void undeploy(String deployedName, boolean force) throws AdminException {
			ModelNode request;
			try {			
				request = buildUndeployRequest(deployedName, force);
	        } catch (OperationFormatException e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70010, e);
	        }
			execute(request);
		}		
		
	    public ModelNode buildUndeployRequest(String name, boolean force) throws OperationFormatException {
	        ModelNode composite = new ModelNode();
	        composite.get("operation").set("composite");
	        composite.get("address").setEmptyList();
	        ModelNode steps = composite.get("steps");

	        DefaultOperationRequestBuilder builder;

	        if(this.domainMode) {
            	final List<String> serverGroups = Util.getServerGroups(this.connection);

                for (String group : serverGroups){
                    ModelNode groupStep = Util.configureDeploymentOperation(DEPLOYMENT_UNDEPLOY_OPERATION, name, group);
                    steps.add(groupStep);
                }

                for (String group : serverGroups) {
                    ModelNode groupStep = Util.configureDeploymentOperation(DEPLOYMENT_REMOVE_OPERATION, name, group);
                    steps.add(groupStep);
                }
	        } else if(Util.isDeployedAndEnabledInStandalone(name, this.connection)||force) {
	            builder = new DefaultOperationRequestBuilder();
	            builder.setOperationName("undeploy");
	            builder.addNode("deployment", name);
	            steps.add(builder.buildRequest());
	        }

	        // remove content
            builder = new DefaultOperationRequestBuilder();
            builder.setOperationName("remove");
            builder.addNode("deployment", name);
            steps.add(builder.buildRequest());
            
	        return composite;
	    }

		@Override
		public void deploy(String deployName, InputStream vdb)	throws AdminException {
			ModelNode request = buildDeployVDBRequest(deployName, vdb, true);
			execute(request);
		}
		
		public void deploy(String deployName, InputStream vdb, boolean persist)	throws AdminException {
			ModelNode request = buildDeployVDBRequest(deployName, vdb, persist);
			execute(request);
		}		

		private ModelNode buildDeployVDBRequest(String fileName, InputStream vdb, boolean persist) throws AdminProcessingException {
            try {
				if (Util.isDeploymentInRepository(fileName, this.connection)){
	                // replace
					DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
	                builder = new DefaultOperationRequestBuilder();
	                builder.setOperationName("full-replace-deployment");
	                builder.addProperty("name", fileName);
	                byte[] bytes = ObjectConverterUtil.convertToByteArray(vdb);
	                builder.getModelNode().get("content").get(0).get("bytes").set(bytes);
	                return builder.buildRequest();                
				}
				
				//add
		        ModelNode composite = new ModelNode();
		        composite.get("operation").set("composite");
		        composite.get("address").setEmptyList();
		        ModelNode steps = composite.get("steps");			
				
				DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
	            builder.setOperationName("add");
	            builder.addNode("deployment", fileName);


				byte[] bytes = ObjectConverterUtil.convertToByteArray(vdb);
				builder.getModelNode().get("content").get(0).get("bytes").set(bytes);
				ModelNode request = builder.buildRequest();
	            if (!persist) {
	            	request.get("persistent").set(false); // prevents writing this deployment out to standalone.xml
	            }		
	            request.get("enabled").set(true);
				steps.add(request);
            
	            // deploy
	            if (this.domainMode) {
	            	List<String> serverGroups = Util.getServerGroups(this.connection);
	                for (String serverGroup : serverGroups) {
	                    steps.add(Util.configureDeploymentOperation("add", fileName, serverGroup));
	                }
	                for (String serverGroup : serverGroups) {
	                    steps.add(Util.configureDeploymentOperation("deploy", fileName, serverGroup));
	                }
	            } else {
	                builder = new DefaultOperationRequestBuilder();
	                builder.setOperationName("deploy");
	                builder.addNode("deployment", fileName);
					request = builder.buildRequest();
		            if (!persist) {
		            	request.get("persistent").set(false); // prevents writing this deployment out to standalone.xml
		            }	        
		            request.get("enabled").set(true);
	                steps.add(request);
	            }     
	            return composite;
			} catch (OperationFormatException e) {
				 throw new AdminProcessingException(AdminPlugin.Event.TEIID70011, e);
			} catch (IOException e) {
				 throw new AdminProcessingException(AdminPlugin.Event.TEIID70011, e);
			}      
		}

		@Override
		public Collection<? extends CacheStatistics> getCacheStats(String cacheType) throws AdminException {
	        final ModelNode request = buildRequest("teiid", "cache-statistics",	"cache-type", cacheType);//$NON-NLS-1$ //$NON-NLS-2$
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (Util.isSuccess(outcome)) {
	            	return getDomainAwareList(outcome, VDBMetadataMapper.CacheStatisticsMetadataMapper.INSTANCE);	            	
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70013, e);
	        }
	        return null;
		}

		@Override
		public Collection<String> getCacheTypes() throws AdminException {
	        final ModelNode request = buildRequest("teiid", "cache-types");//$NON-NLS-1$ //$NON-NLS-2$
	        return new HashSet<String>(executeList(request));
		}

		private Collection<String> executeList(final ModelNode request)	throws AdminProcessingException {
			try {
	            ModelNode outcome = this.connection.execute(request);
	            if (Util.isSuccess(outcome)) {
	            	return Util.getList(outcome);
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70014, e);
	        }
	        return Collections.emptyList();
		}

		private List<String> getChildNodeNames(String subsystem, String childNode) throws AdminProcessingException {
	        final ModelNode request = buildRequest(subsystem, "read-children-names", "child-type", childNode);//$NON-NLS-1$
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (Util.isSuccess(outcome)) {
	                return Util.getList(outcome);
	            }
	        } catch (IOException e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70015, e);
	        }
	        return Collections.emptyList();	
			
		}
		
		/**
		 * /subsystem=datasources:read-children-names(child-type=data-source)
		 * /subsystem=resource-adapters/resource-adapter={rar-file}:read-resource
		 * @see org.teiid.adminapi.Admin#getDataSourceNames()
		 */
		@Override
		public Collection<String> getDataSourceNames() throws AdminException {
			Set<String> datasourceNames = new HashSet<String>();
			datasourceNames.addAll(getChildNodeNames("datasources", "data-source"));
			datasourceNames.addAll(getChildNodeNames("datasources", "xa-data-source"));
			datasourceNames.addAll(getResourceAdapterDataSources().keySet());
			
			Set<String> dsNames = new HashSet<String>();
			for (String s:datasourceNames) {
				if (s.startsWith(JAVA_CONTEXT)) {
					dsNames.add(s.substring(6));
				}
				else {
					dsNames.add(s);
				}
			}
	        return dsNames;	
		}

		private Map<String, String> getResourceAdapterDataSources() throws AdminException {
			HashMap<String, String> datasourceNames = new HashMap<String, String>();
			Set<String> resourceAdapters = getDeployedResourceAdapterNames();
			for (String resource:resourceAdapters) {
				getResourceAdpaterConnections(datasourceNames, resource);
			}
			return datasourceNames;
		}

		private void getResourceAdpaterConnections(HashMap<String, String> datasourceNames, String rarName) throws AdminProcessingException {
			DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
			try {
	        	addProfileNode(builder);				
			    builder.addNode("subsystem", "resource-adapters"); //$NON-NLS-1$ //$NON-NLS-2$
			    builder.addNode("resource-adapter", rarName); //$NON-NLS-1$ //$NON-NLS-2$
			    builder.setOperationName("read-resource"); 
			    ModelNode request = builder.buildRequest();
			    
			    ModelNode outcome = this.connection.execute(request);
			    if (Util.isSuccess(outcome)) {
			    	if (outcome.hasDefined("result")) {
			    		ModelNode result = outcome.get("result");
			        	if (result.hasDefined("connection-definitions")) {
			        		List<ModelNode> connDefs = result.get("connection-definitions").asList();
			        		for (ModelNode conn:connDefs) {
			        			Iterator<String> it = conn.keys().iterator();
			        			if (it.hasNext()) {
			        				datasourceNames.put(it.next(), rarName);
			        			}
			        		}
			        	}
			    	}
			    }
			} catch (OperationFormatException e) {
			     throw new AdminProcessingException(AdminPlugin.Event.TEIID70016, e, AdminPlugin.Util.gs(AdminPlugin.Event.TEIID70016));
			} catch (IOException e) {
				 throw new AdminProcessingException(AdminPlugin.Event.TEIID70016, e, AdminPlugin.Util.gs(AdminPlugin.Event.TEIID70016));
			}
		}
		
		/**
		 * This will get all deployed RAR names
		 * /subsystem=resource-adapters:read-children-names(child-type=resource-adapter)
		 * @return
		 * @throws AdminException
		 */
		private Set<String> getDeployedResourceAdapterNames() throws AdminProcessingException {
			Set<String> templates = new HashSet<String>();
			templates.addAll(getChildNodeNames("resource-adapters", "resource-adapter"));
	        return templates;					
		}

		// :read-children-names(child-type=deployment)
		private Set<String> getAvailableResourceAdapterNames() throws AdminProcessingException {
			List<String> deployments = getChildNodeNames(null, "deployment");
			Set<String> templates = new HashSet<String>();
            for (String deployment:deployments) {
            	if (deployment.endsWith(".rar")) {
            		templates.add(deployment);
            	}
            }
	        return templates;
		}
		
		public List<String> getDeployments(){
			return Util.getDeployments(this.connection);
		}

		@Override
		public Set<String> getDataSourceTemplateNames() throws AdminProcessingException {
			Set<String> templates = new HashSet<String>();
			templates.addAll(getInstalledJDBCDrivers());
			templates.addAll(getAvailableResourceAdapterNames());
			return templates;
		}
		
		@Override
		public Collection<? extends WorkerPoolStatistics> getWorkerPoolStats() throws AdminProcessingException {
			final ModelNode request = buildRequest("teiid", "workerpool-statistics");//$NON-NLS-1$
			if (request != null) {
		        try {
		            ModelNode outcome = this.connection.execute(request);
		            if (Util.isSuccess(outcome)) {
		            	return getDomainAwareList(outcome, VDBMetadataMapper.WorkerPoolStatisticsMetadataMapper.INSTANCE);
		            }		            
		        } catch (Exception e) {
		        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70020, e);
		        }
			}
	        return null;
		}		

		
		@Override
		public void cancelRequest(String sessionId, long executionId) throws AdminProcessingException {
			final ModelNode request = buildRequest("teiid", "terminate-session", "session", sessionId, "execution-id", String.valueOf(executionId));//$NON-NLS-1$
			if (request == null) {
				return;
			}
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70021, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70022, e);
	        }		
		}
		
		@Override
		public Collection<? extends Request> getRequests() throws AdminException {
			final ModelNode request = buildRequest("teiid", "list-requests");//$NON-NLS-1$
			if (request != null) {
		        try {
		            ModelNode outcome = this.connection.execute(request);
		            if (Util.isSuccess(outcome)) {
		                return getDomainAwareList(outcome, RequestMetadataMapper.INSTANCE);
		            }
		        } catch (Exception e) {
		        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70023, e);
		        }
			}
	        return Collections.emptyList();
		}

		@Override
		public Collection<? extends Request> getRequestsForSession(String sessionId) throws AdminException {
			final ModelNode request = buildRequest("teiid", "list-requests-per-session", "session", sessionId);//$NON-NLS-1$
			if (request != null) {
		        try {
		            ModelNode outcome = this.connection.execute(request);
		            if (Util.isSuccess(outcome)) {
		                return getDomainAwareList(outcome, RequestMetadataMapper.INSTANCE);
		            }
		        } catch (Exception e) {
		        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70024, e);
		        }
			}
	        return Collections.emptyList();
		}

		@Override
		public Collection<? extends Session> getSessions() throws AdminException {
			final ModelNode request = buildRequest("teiid", "list-sessions");//$NON-NLS-1$
			if (request != null) {
		        try {
		            ModelNode outcome = this.connection.execute(request);
		            if (Util.isSuccess(outcome)) {
		                return getDomainAwareList(outcome, SessionMetadataMapper.INSTANCE);
		            }
		        } catch (Exception e) {
		        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70025, e);
		        }
			}
	        return Collections.emptyList();
		}

		/**
		 * pattern on CLI
		 * /subsystem=datasources/data-source=foo:read-resource-description
		 */
		@Override		
		public Collection<PropertyDefinition> getTemplatePropertyDefinitions(String templateName) throws AdminException {

			ModelNode request = null;
			ModelNode result = null;
			try {
				Set<String> resourceAdapters = getAvailableResourceAdapterNames();
	        	if (resourceAdapters.contains(templateName)) {
	        		DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
		        	addProfileNode(builder);	        		
		            builder.addNode("subsystem", "teiid"); //$NON-NLS-1$ //$NON-NLS-2$
		            builder.setOperationName("read-rar-description"); //$NON-NLS-1$
		            builder.addProperty("rar-name", templateName);
		            request = builder.buildRequest();					
			        try {
			            ModelNode outcome = this.connection.execute(request);
			            if (!Util.isSuccess(outcome)) {
			                 throw new AdminProcessingException(AdminPlugin.Event.TEIID70026, Util.getFailureDescription(outcome));
			            }
			            result = outcome.get("result");
			        } catch (IOException e) {
			        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70027, e);
			        }			            
	        	}
	        	else {
	        		result = new ModelNode();
	        		result.add(buildProperty("connection-url", "Connection URL", ModelType.STRING, "connection url to database", true));
	        		result.add(buildProperty("user-name", "User Name", ModelType.STRING, "user name", false));
	        		result.add(buildProperty("password", "Password", ModelType.STRING, "password", false));
	        		result.add(buildProperty("check-valid-connection-sql", "Connection Validate SQL", ModelType.STRING, "SQL to be used to validate the connection", false));
	        	}
	        	
	        } catch (OperationFormatException e) {
	            throw new IllegalStateException("Failed to build operation", e); //$NON-NLS-1$
	        }
	        return buildPropertyDefinitions(result.asList());
		}
		
		private ModelNode buildProperty(String name, String displayName, ModelType modelType, String description, boolean required) {
			ModelNode node = new ModelNode();
			node.get(name, "type").set(modelType);
	        node.get(name, "description").set(description);
	        node.get(name, "required").set(required);
	        node.get(name, "display").set(displayName);
	        return node;
		}

		private ArrayList<PropertyDefinition> buildPropertyDefinitions(List<ModelNode> propsNodes) {
			ArrayList<PropertyDefinition> propDefinitions = new ArrayList<PropertyDefinition>();
        	for (ModelNode node:propsNodes) {
        		PropertyDefinitionMetadata def = new PropertyDefinitionMetadata();
        		Set<String> keys = node.keys();
        		
        		String name = keys.iterator().next();
        		def.setName(name);
        		node = node.get(name);

        		if (node.hasDefined("display")) {
        			def.setDisplayName(node.get("display").asString());
        		}
        		
        		if (node.hasDefined("description")) {
        			def.setDescription(node.get("description").asString());
        		}
        		
        		if (node.hasDefined("allowed")) {
        			List<ModelNode> allowed = node.get("allowed").asList();
        			ArrayList<String> list = new ArrayList<String>();
        			for(ModelNode m:allowed) {
        				list.add(m.asString());
        			}
        			def.setAllowedValues(list);
        		}        		
        		
        		if (node.hasDefined("required")) {
        			def.setRequired(node.get("required").asBoolean());
        		}
        		
        		if (node.hasDefined("read-only")) {
        			String access = node.get("read-only").asString();
        			def.setModifiable(Boolean.parseBoolean(access));
        		}
        		
        		if (node.hasDefined("advanced")) {
        			String access = node.get("advanced").asString();
        			def.setAdvanced(Boolean.parseBoolean(access));
        		}        		
        		
        		if (node.hasDefined("masked")) {
        			String access = node.get("masked").asString();
        			def.setAdvanced(Boolean.parseBoolean(access));
        		} 
        		
        		if (node.hasDefined("restart-required")) {
        			def.setRequiresRestart(RestartType.NONE);
        		}

        		String type = node.get("type").asString();
        		if (ModelType.STRING.name().equals(type)) {
        			def.setPropertyTypeClassName(String.class.getName());
        		}
        		else if (ModelType.INT.name().equals(type)) {
        			def.setPropertyTypeClassName(Integer.class.getName());
        		}
        		else if (ModelType.LONG.name().equals(type)) {
        			def.setPropertyTypeClassName(Long.class.getName());
        		}        		
        		else if (ModelType.BOOLEAN.name().equals(type)) {
        			def.setPropertyTypeClassName(Boolean.class.getName());
        		}
        		else if (ModelType.BIG_INTEGER.name().equals(type)) {
        			def.setPropertyTypeClassName(BigInteger.class.getName());
        		}        		
        		else if (ModelType.BIG_DECIMAL.name().equals(type)) {
        			def.setPropertyTypeClassName(BigDecimal.class.getName());
        		}     
        		
        		if (node.hasDefined("default")) {
            		if (ModelType.STRING.name().equals(type)) {
            			def.setDefaultValue(node.get("default").asString());
            		}
            		else if (ModelType.INT.name().equals(type)) {
            			def.setDefaultValue(node.get("default").asInt());
            		}
            		else if (ModelType.LONG.name().equals(type)) {
            			def.setDefaultValue(node.get("default").asLong());
            		}        		
            		else if (ModelType.BOOLEAN.name().equals(type)) {
            			def.setDefaultValue(node.get("default").asBoolean());
            		}
            		else if (ModelType.BIG_INTEGER.name().equals(type)) {
            			def.setDefaultValue(node.get("default").asBigInteger());
            		}        		
            		else if (ModelType.BIG_DECIMAL.name().equals(type)) {
            			def.setDefaultValue(node.get("default").asBigDecimal());
            		}          			
        		}
        		propDefinitions.add(def);
        	}
			return propDefinitions;
		}

		@Override
		public Collection<? extends Transaction> getTransactions() throws AdminException {
			final ModelNode request = buildRequest("teiid", "list-transactions");//$NON-NLS-1$
			if (request != null) {
		        try {
		            ModelNode outcome = this.connection.execute(request);
		            if (Util.isSuccess(outcome)) {
		                return getDomainAwareList(outcome, TransactionMetadataMapper.INSTANCE);
		            }
		        } catch (Exception e) {
		        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70028, e);
		        }
			}
	        return Collections.emptyList();
		}
		
		@Override
		public void terminateSession(String sessionId) throws AdminException {
			final ModelNode request = buildRequest("teiid", "terminate-session", "session", sessionId);//$NON-NLS-1$
			if (request == null) {
				return;
			}
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70029, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70030, e);
	        }			
		}

		@Override
		public void terminateTransaction(String transactionId) throws AdminException {
			final ModelNode request = buildRequest("teiid", "terminate-transaction", "xid", transactionId);//$NON-NLS-1$
			if (request == null) {
				return;
			}
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70031, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70032, e);
	        }			
		}		

		@Override
		public Translator getTranslator(String deployedName) throws AdminException {
			final ModelNode request = buildRequest("teiid", "get-translator", "translator-name", deployedName);//$NON-NLS-1$
			if (request == null) {
				return null;
			}
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (Util.isSuccess(outcome)) {
	            	if (outcome.hasDefined("result")) {
	            		if (this.domainMode) {
		            		List<VDBTranslatorMetaData> list = getDomainAwareList(outcome, VDBMetadataMapper.VDBTranslatorMetaDataMapper.INSTANCE);
		            		if (list != null && !list.isEmpty()) {
		            			return list.get(0);
		            		}
	            		}
	            		else {
		            		ModelNode result = outcome.get("result");
		            		return VDBMetadataMapper.VDBTranslatorMetaDataMapper.INSTANCE.unwrap(result);
	            		}
	            	}	            	
	            }
	            
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70033, e);
	        }			
			return null;
		}

		@Override
		public Collection<? extends Translator> getTranslators() throws AdminException {
	        final ModelNode request = buildRequest("teiid", "list-translators");//$NON-NLS-1$ //$NON-NLS-2$
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (Util.isSuccess(outcome)) {
	                return getDomainAwareList(outcome, VDBMetadataMapper.VDBTranslatorMetaDataMapper.INSTANCE);
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70034, e);
	        }

	        return Collections.emptyList();
		}
		
		private ModelNode buildRequest(String subsystem, String operationName, String... params) throws AdminProcessingException {
			DefaultOperationRequestBuilder builder = new DefaultOperationRequestBuilder();
	        final ModelNode request;
	        try {
	        	if (subsystem != null) {
		        	addProfileNode(builder);	        		        	
		            builder.addNode("subsystem", subsystem); //$NON-NLS-1$ //$NON-NLS-2$
	        	}
	            builder.setOperationName(operationName); 
	            request = builder.buildRequest();
	            if (params != null && params.length % 2 == 0) {
	            	for (int i = 0; i < params.length; i+=2) {
	            		builder.addProperty(params[i], params[i+1]);
	            	}
	            }
	        } catch (OperationFormatException e) {
	            throw new IllegalStateException("Failed to build operation", e); //$NON-NLS-1$
	        }
			return request;
		}
		
		private <T> List<T> getDomainAwareList(ModelNode operationResult,  MetadataMapper<T> mapper) {
	    	if (this.domainMode) {
	    		List<T> returnList = new ArrayList<T>();
	    		
	    		ModelNode serverGroups = operationResult.get("server-groups");
	    		Set<String> serverGroupNames = serverGroups.keys();
	    		for (String serverGroupName:serverGroupNames) {
	    			ModelNode serverGroup = serverGroups.get(serverGroupName);
	    			Set<String> serverNames = serverGroup.keys();
	    			for (String serverName:serverNames) {
	    				ModelNode server = serverGroup.get(serverName);
	    				String hostName = server.get("host").asString();
	    				if (server.get("response", "outcome").asString().equals(Util.SUCCESS)) {
	    					ModelNode result = server.get("response", "result");
	    					if (result.isDefined()) {
	    				        List<ModelNode> nodeList = result.asList(); //$NON-NLS-1$
	    				        for(ModelNode node : nodeList) {
	    				        	T anObj = mapper.unwrap(node);
	    				        	if (anObj instanceof DomainAware) {
	    				        		((AdminObjectImpl)anObj).setServerGroup(serverGroupName);
	    				        		((AdminObjectImpl)anObj).setServerName(serverName);
	    				        		((AdminObjectImpl)anObj).setHostName(hostName);
	    				        	}
	    				        	returnList.add(anObj);
	    				        }
	    						
	    					}
	    				}
	    			}
	    		}
	    		return returnList;
	    	}			
	    	return getList(operationResult, mapper);
		}
		
	    private <T> List<T> getList(ModelNode operationResult,  MetadataMapper<T> mapper) {
	        if(!operationResult.hasDefined("result")) //$NON-NLS-1$
	            return Collections.emptyList();

	        List<ModelNode> nodeList = operationResult.get("result").asList(); //$NON-NLS-1$
	        if(nodeList.isEmpty())
	            return Collections.emptyList();

	        List<T> list = new ArrayList<T>(nodeList.size());
	        for(ModelNode node : nodeList) {
        		list.add(mapper.unwrap(node));
	        }
	        return list;
	    }		
	    
  

		@Override
		public VDB getVDB(String vdbName, int vdbVersion) throws AdminException {
			final ModelNode request = buildRequest("teiid", "get-vdb", "vdb-name", vdbName, "vdb-version", String.valueOf(vdbVersion));//$NON-NLS-1$
			if (request == null) {
				return null;
			}
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (Util.isSuccess(outcome)) {
	            	if (this.domainMode) {
		            	List<VDBMetaData> list = getDomainAwareList(outcome, VDBMetadataMapper.INSTANCE);
		            	if (list != null && !list.isEmpty()) {
		            		return list.get(0);
		            	}
	            	}
	            	else {
		            	if (outcome.hasDefined("result")) {
		            		ModelNode result = outcome.get("result");
		            		return VDBMetadataMapper.INSTANCE.unwrap(result);
		            	}	     
	            	}
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70035, e);
	        }			
			return null;
		}

		@Override
		public List<? extends VDB> getVDBs() throws AdminException {
	        final ModelNode request = buildRequest("teiid", "list-vdbs");//$NON-NLS-1$ //$NON-NLS-2$
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (Util.isSuccess(outcome)) {
	                return getDomainAwareList(outcome, VDBMetadataMapper.INSTANCE);
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70036, e);
	        }

	        return Collections.emptyList();
		}

		@Override
		public void addDataRoleMapping(String vdbName, int vdbVersion, String dataRole, String mappedRoleName) throws AdminException {
	        final ModelNode request = buildRequest("teiid", "add-data-role", 
	        		"vdb-name", vdbName,
	        		"vdb-version", String.valueOf(vdbVersion),
	        		"data-role", dataRole,
	        		"mapped-role", mappedRoleName);//$NON-NLS-1$ //$NON-NLS-2$
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70039, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70040, e);
	        }
		}		
		
		@Override
		public void removeDataRoleMapping(String vdbName, int vdbVersion, String dataRole, String mappedRoleName) throws AdminException {
	        final ModelNode request = buildRequest("teiid", "remove-data-role", 
	        		"vdb-name", vdbName,
	        		"vdb-version", String.valueOf(vdbVersion),
	        		"data-role", dataRole,
	        		"mapped-role", mappedRoleName);//$NON-NLS-1$ //$NON-NLS-2$
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70041, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70042, e);
	        }		
		}

		@Override
		public void setAnyAuthenticatedForDataRole(String vdbName, int vdbVersion, String dataRole, boolean anyAuthenticated) throws AdminException {
	        ModelNode request = buildRequest("teiid", "add-anyauthenticated-role", 
	        		"vdb-name", vdbName,
	        		"vdb-version", String.valueOf(vdbVersion),
	        		"data-role", dataRole); //$NON-NLS-1$ //$NON-NLS-2$
	        
	        if (!anyAuthenticated) {
	        	request = buildRequest("teiid", "remove-anyauthenticated-role", 
		        		"vdb-name", vdbName,
		        		"vdb-version", String.valueOf(vdbVersion),
		        		"data-role", dataRole); //$NON-NLS-1$ //$NON-NLS-2$	        	
	        }
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70043, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70044, e);
	        }			
		}

		@Override
		public void changeVDBConnectionType(String vdbName, int vdbVersion, ConnectionType type) throws AdminException {
	        final ModelNode request = buildRequest("teiid", "change-vdb-connection-type", 
	        		"vdb-name", vdbName,
	        		"vdb-version", String.valueOf(vdbVersion),
	        		"connection-type", type.name());//$NON-NLS-1$ //$NON-NLS-2$
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70045, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70046, e);
	        }				
		}		
		
		@Override
		public void assignToModel(String vdbName, int vdbVersion, String modelName, String sourceName, String translatorName,
				String dsName) throws AdminException {
	        final ModelNode request = buildRequest("teiid", "assign-datasource", 
	        		"vdb-name", vdbName,
	        		"vdb-version", String.valueOf(vdbVersion),
	        		"model-name", modelName,
	        		"source-name", sourceName,
	        		"translator-name", translatorName,
	        		"ds-name", dsName);//$NON-NLS-1$ //$NON-NLS-2$
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70047, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70048, e);
	        }				
		}
		
	    @Override
	    public void markDataSourceAvailable(String jndiName) throws AdminException {
	        final ModelNode request = buildRequest("teiid", "mark-datasource-available","ds-name", jndiName);//$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70049, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70050, e);
	        }	    	
	    }

		@Override
		public void restartVDB(String vdbName, int vdbVersion, String... models) throws AdminException {
			ModelNode request = null;
			String modelNames = null;
			
			if (models != null && models.length > 0) {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < models.length-1; i++) {
					sb.append(models[i]).append(",");
				}
				sb.append(models[models.length-1]);
				modelNames = sb.toString();
			}
	        
			if (modelNames != null) {
				request = buildRequest("teiid", "restart-vdb", 
		        		"vdb-name", vdbName,
		        		"vdb-version", String.valueOf(vdbVersion),
		        		"model-names", modelNames);//$NON-NLS-1$ //$NON-NLS-2$
			}
			else {
				request = buildRequest("teiid", "restart-vdb", 
		        		"vdb-name", vdbName,
		        		"vdb-version", String.valueOf(vdbVersion));//$NON-NLS-1$ 			
			}
	        
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70045, Util.getFailureDescription(outcome));
	            }
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70046, e);
	        }		        
		}

		@Override
		public String getSchema(String vdbName, int vdbVersion,
				String modelName, EnumSet<SchemaObjectType> allowedTypes,
				String typeNamePattern) throws AdminException {
			ModelNode request = null;
			
			ArrayList<String> params = new ArrayList<String>();
			params.add("vdb-name");
			params.add(vdbName);
			params.add("vdb-version");
			params.add(String.valueOf(vdbVersion));
			params.add("model-name");
			params.add(modelName);
			
			if (allowedTypes != null) {
				params.add("entity-type");
				StringBuilder sb = new StringBuilder();
				for (SchemaObjectType type:allowedTypes) {
					if (sb.length() > 0) {
						sb.append(",");
					}
					sb.append(type.name());
				}
				params.add(sb.toString());
			}
			
			if (typeNamePattern != null) {
				params.add("entity-pattern");
				params.add(typeNamePattern);
			}
			
			request = buildRequest("teiid", "get-schema", params.toArray(new String[params.size()]));//$NON-NLS-1$ //$NON-NLS-2$
			
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70045, Util.getFailureDescription(outcome));
	            }
	            return outcome.get(RESULT).asString();
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70046, e);
	        }
		}

		@Override
		public String getQueryPlan(String sessionId, int executionId)  throws AdminException {
			final ModelNode request = buildRequest("teiid", "get-plan", "session", sessionId, "execution-id", String.valueOf(executionId));//$NON-NLS-1$
			if (request == null) {
				return null;
			}
	        try {
	            ModelNode outcome = this.connection.execute(request);
	            if (!Util.isSuccess(outcome)) {
	            	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70021, Util.getFailureDescription(outcome));
	            }
	            return outcome.get(RESULT).asString();
	        } catch (Exception e) {
	        	 throw new AdminProcessingException(AdminPlugin.Event.TEIID70022, e);
	        }
		}		
    }
}
