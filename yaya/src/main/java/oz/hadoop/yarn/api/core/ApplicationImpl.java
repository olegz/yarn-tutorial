/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package oz.hadoop.yarn.api.core;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FairScheduler;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;
import org.json.simple.JSONObject;
import org.springframework.util.StringUtils;

import oz.hadoop.yarn.api.YarnApplication;
import oz.hadoop.yarn.api.YayaConstants;
import oz.hadoop.yarn.api.net.ClientServer;
import oz.hadoop.yarn.api.utils.JarUtils;
import oz.hadoop.yarn.api.utils.PrimitiveImmutableTypeMap;
import oz.hadoop.yarn.api.utils.PrintUtils;
import oz.hadoop.yarn.api.utils.ReflectionUtils;

/**
 * @author Oleg Zhurakousky
 *
 */
class ApplicationImpl<T> implements YarnApplication<T> {
	
	private final Log logger = LogFactory.getLog(this.getClass().getName());

	private final Map<String, Object> applicationSpecification;
	
	private final YarnConfiguration yarnConfig;
	
	private final YarnClient yarnClient;
	
	private final String applicationName;
	
	private final String applicationMasterLauncher;;
	
	private final boolean local;
	
	private final PrimitiveImmutableTypeMap applicationContainerSpecification;
	
	private volatile ClientServer clientServer;

	/**
	 * 
	 * @param applicationSpecification
	 */
	@SuppressWarnings("unchecked")
	ApplicationImpl(Map<String, Object> applicationSpecification){
		this.applicationSpecification = applicationSpecification;
		this.yarnConfig = (YarnConfiguration) this.applicationSpecification.get(YayaConstants.YARN_CONFIG);
		this.applicationName = (String) this.applicationSpecification.get(YayaConstants.APPLICATION_NAME);
		this.yarnClient = YarnClient.createYarnClient();
		this.local = this.yarnConfig == null;
		if (local){
			this.applicationSpecification.put(YayaConstants.LOCAL, true);
		}
		this.applicationMasterLauncher = ApplicationMasterLauncher.class.getName();
		this.applicationContainerSpecification = new PrimitiveImmutableTypeMap((Map<String, Object>) this.applicationSpecification.get(YayaConstants.CONTAINER_SPEC));
		// no need to pass it to the AM and Containers and it also creates unnecessary serialization-to-json issue. 
		// We only needed up until this point to determine if execution is local.
		this.applicationSpecification.remove(YayaConstants.YARN_CONFIG); 
	}
	
	/**
	 * 
	 */
	@Override
	public PrimitiveImmutableTypeMap getApplicationSpecification() {
		return new PrimitiveImmutableTypeMap(this.applicationSpecification);
	}

	/**
	 * 
	 */
	@SuppressWarnings("unchecked")
	@Override
	public T launch() {
		if (logger.isDebugEnabled()){
			logger.debug("Launching application '" + this.applicationName + "' with the following spec: \n**********\n" + 
					this.prettyMap(this.applicationSpecification) + "\n**********");
		}

		int containerCount = this.applicationContainerSpecification.getInt(YayaConstants.CONTAINER_COUNT);
		
		boolean voidLaunch = false;
		if (StringUtils.hasText(this.applicationContainerSpecification.getString(YayaConstants.COMMAND)) ||
				this.applicationContainerSpecification.getString(YayaConstants.CONTAINER_ARG) != null){
			voidLaunch = true;
		}
		
		if (!voidLaunch){
			InetSocketAddress address = this.buildSocketAddress();

			this.clientServer = this.buildClientServer(address, containerCount);
			// will return the actual address
			address = clientServer.start();
			
			this.applicationSpecification.put(YayaConstants.CLIENT_HOST, address.getHostName());
			this.applicationSpecification.put(YayaConstants.CLIENT_PORT, address.getPort());
		}
		
		if (this.local){
			this.launchLocalApplicationMaster(containerCount);
		}
		else {
			this.launchApplicationMaster(containerCount);
		}
		
		if (!voidLaunch){
			if (!clientServer.awaitAllClients(300)){ 
				throw new IllegalStateException("Failed to connect with all clients within 5 min");
			}
			return (T) clientServer.getContainerDelegates();
		}
		else {
			return null;
		}
	}
	
	/**
	 * 
	 */
	@Override
	public void shutDown() {
		if (this.applicationContainerSpecification.get(YayaConstants.CONTAINER_ARG) == null &&
				!StringUtils.hasText(this.applicationContainerSpecification.getString(YayaConstants.COMMAND))){
			this.clientServer.stop();
			// no need to call close() since it simply delegates to stop()???? Beats me
		}
		this.yarnClient.stop();
	}
	
	/**
	 * 
	 */
	@SuppressWarnings("unchecked")
	private void launchLocalApplicationMaster(int containerCount){
		this.applicationSpecification.put(YayaConstants.APP_ID, new Random().nextInt(1000));
		String jsonArguments = JSONObject.toJSONString(this.applicationSpecification);
		String encodedJsonArguments = new String(Base64.encodeBase64(jsonArguments.getBytes()));
		try {
			/*
			 * While it would be better/simpler to avoid Reflection here, the following code emulates as close as possible
			 * the way YARN invokes Application Master. So its done primarily for consistency
			 */
			Class<AbstractContainerLauncher> applicationMasterLauncher = (Class<AbstractContainerLauncher>) Class.forName(this.applicationMasterLauncher);
			Method mainMethod = ReflectionUtils.getMethodAndMakeAccessible(applicationMasterLauncher, "main", new Class[] {String[].class});
			mainMethod.invoke(null, (Object)new String[]{encodedJsonArguments, this.applicationMasterLauncher});
		} 
		catch (Exception e) {
			throw new IllegalStateException("Failed to launch Application Master: " + this.applicationName, e);
		}
	}
	
	/**
	 * 
	 */
	private void launchApplicationMaster(int containerCount) {
		this.startYarnClient();
		
		this.preCheck();
		
		// TODO see if these calls could be made ASYNC since they take time, but always succeed even if cluster is not running.
		
		YarnClientApplication yarnClientApplication = this.createYarnClientApplication();
		ApplicationSubmissionContext appContext = this.initApplicationContext(yarnClientApplication);
		logger.info("Deploying ApplicationMaster");
	    try {
	    	this.yarnClient.submitApplication(appContext);
		}
	    catch (Exception e) {
			throw new IllegalStateException("Failed to launch Application Master: " + this.applicationName, e);
		}
	}
	
	/**
	 * 
	 */
	private void startYarnClient() {
		this.yarnClient.init(this.yarnConfig);
		this.yarnClient.start();
		logger.info("Started YarnClient");
	}
	
	/**
	 * 
	 */
	private String prettyMap(Map<String, Object> m) {
		return PrintUtils.prettyMap(m);
	}
	
	/**
	 * Any type of pre-check you want to perform before launching Application Master
	 * mainly for the purpose of logging warning messages
	 */
	private void preCheck(){
		if (this.applicationContainerSpecification.getInt(YayaConstants.VIRTUAL_CORES) > 1){
			if (!this.yarnConfig.get(YarnConfiguration.RM_SCHEDULER).equals(FairScheduler.class.getName())){
				logger.warn("Based on current Hadoop implementation " +
						"'vcore' settings are ignored for schedulers other then FairScheduler");
			}
		}
		try {
			Iterator<QueueInfo> queues = this.yarnClient.getAllQueues().iterator();
			String identifiedQueueName = (String) this.applicationSpecification.get(YayaConstants.QUEUE_NAME);
			boolean queueExist = false;
			while (!queueExist && queues.hasNext()) {
				QueueInfo queueInfo = queues.next();
				if (queueInfo.getQueueName().equals(identifiedQueueName)){
					queueExist = true;
				}
			}
			if (!queueExist){
				throw new IllegalArgumentException("Queue with the name '" + identifiedQueueName + "' does not exist. Aborting application launch.");
			}
		} 
		catch (Exception e) {
			throw new IllegalStateException("Failed to validate queue.", e);
		}
	}
	
	/**
	 *
	 */
	private YarnClientApplication createYarnClientApplication(){
		try {
			// TODO put a log message about trying to establish the connection to RM
			// TODO could do a simple Connect test to the ResourceManager (e.g., 8055) and throw an exception
			YarnClientApplication yarnClientApplication = this.yarnClient.createApplication();
			logger.debug("Created YarnClientApplication");
			return yarnClientApplication;
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to create YarnClientApplication", e);
		}
	}
	
	/**
	 *
	 */
	private ApplicationSubmissionContext initApplicationContext(YarnClientApplication yarnClientApplication){
		ApplicationSubmissionContext appContext = yarnClientApplication.getApplicationSubmissionContext();
	    appContext.setApplicationName(this.applicationName);
	    ApplicationId appId = appContext.getApplicationId();
	    this.applicationSpecification.put(YayaConstants.APP_ID, appId.getId());
	  
	    ContainerLaunchContext containerLaunchContext = Records.newRecord(ContainerLaunchContext.class);

	    Map<String, LocalResource> localResources = this.createLocalResources(appId);
	    if (logger.isDebugEnabled()){
	    	logger.debug("Created LocalResources: " + localResources);
	    }
	    containerLaunchContext.setLocalResources(localResources);
	    String jsonArguments = JSONObject.toJSONString(this.applicationSpecification);
		String encodedJsonArguments = new String(Base64.encodeBase64(jsonArguments.getBytes()));
		
		YayaUtils.inJvmPrep("JAVA", containerLaunchContext, this.applicationMasterLauncher, encodedJsonArguments);

	    String applicationMasterLaunchCommand = this.createApplicationMasterLaunchCommand(localResources, encodedJsonArguments);
	    containerLaunchContext.setCommands(Collections.singletonList(applicationMasterLaunchCommand));

		Priority priority = Records.newRecord(Priority.class);
		priority.setPriority((int) this.applicationSpecification.get(YayaConstants.PRIORITY));
	    Resource capability = Records.newRecord(Resource.class);
	    capability.setMemory((int) this.applicationSpecification.get(YayaConstants.MEMORY));
	    capability.setVirtualCores((int) this.applicationSpecification.get(YayaConstants.VIRTUAL_CORES));
	    appContext.setResource(capability);
	    appContext.setMaxAppAttempts((int) this.applicationSpecification.get(YayaConstants.MAX_ATTEMPTS));
	    appContext.setAMContainerSpec(containerLaunchContext);
	    appContext.setPriority(priority);
	    appContext.setQueue((String) this.applicationSpecification.get(YayaConstants.QUEUE_NAME)); 

	    if (logger.isInfoEnabled()){
	    	logger.info("Created ApplicationSubmissionContext: " + appContext);
	    }

		return appContext;
	}
	
	/**
	 * Will generate the final launch command for this ApplicationMaster
	 */
	private String createApplicationMasterLaunchCommand(Map<String, LocalResource> localResources, String containerSpecStr) {
		String classpath = YayaUtils.calculateClassPath(localResources);
		if (logger.isInfoEnabled()){
			logger.info("Application master classpath: " + classpath);
		}

		String applicationMasterLaunchCommand = YayaUtils.generateExecutionCommand(
				this.applicationContainerSpecification.getString(YayaConstants.JAVA_COMMAND) + " -cp ",
				classpath,
				this.applicationMasterLauncher,
				containerSpecStr,
				this.applicationName,
				"_AM_");

		if (logger.isInfoEnabled()){
			logger.info("Application Master launch command: " + applicationMasterLaunchCommand);
		}

	    return applicationMasterLaunchCommand;
	}
	
	/**
	 * Will package this application JAR in {@link LocalResource}s.
	 * TODO make it more general to allow other resources
	 */
	private Map<String, LocalResource> createLocalResources(ApplicationId appId) {
		Map<String, LocalResource> localResources = new LinkedHashMap<String, LocalResource>();

		try {
			FileSystem fs = FileSystem.get(this.yarnConfig);

			String[] cp = System.getProperty("java.class.path").split(":");
			for (String v : cp) {
				File f = new File(v);
				if (f.isDirectory()) {
					String jarFileName = YayaUtils.generateJarFileName(this.applicationName);
					if (logger.isDebugEnabled()){
						logger.debug("Creating JAR: " + jarFileName);
					}
					File jarFile = JarUtils.toJar(f, jarFileName);
					this.addToLocalResources(fs, jarFile.getAbsolutePath(),jarFile.getName(), appId.getId(), localResources);
					try {
						new File(jarFile.getAbsolutePath()).delete(); // will delete the generated JAR file
					}
					catch (Exception e) {
						logger.warn("Failed to delete generated JAR file: " + jarFile.getAbsolutePath(), e);
					}
				}
				else {
					this.addToLocalResources(fs, f.getAbsolutePath(), f.getName(), appId.getId(), localResources);
				}
			}
		}
	    catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return localResources;
	}
	
	/**
	 *
	 */
	private void addToLocalResources(FileSystem fs, String fileSrcPath, String fileDstPath, int appId, Map<String, LocalResource> localResources) {
		String suffix = this.applicationName + "_master/" + appId + "/" + fileDstPath;
		Path dst = new Path(fs.getHomeDirectory(), suffix);

		try {
			fs.copyFromLocalFile(new Path(fileSrcPath), dst);
			FileStatus scFileStatus = fs.getFileStatus(dst);
			LocalResource scRsrc = LocalResource.newInstance(ConverterUtils.getYarnUrlFromURI(dst.toUri()),
					LocalResourceType.FILE, LocalResourceVisibility.APPLICATION, scFileStatus.getLen(), scFileStatus.getModificationTime());
			localResources.put(fileDstPath, scRsrc);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to communicate with FileSystem: " + fs, e);
		}
	}
	
	/**
	 * 
	 */
	private InetSocketAddress buildSocketAddress(){
		try {
			InetSocketAddress address = new InetSocketAddress(InetAddress.getLocalHost().getCanonicalHostName(), 0);
			return address;
		} 
		catch (UnknownHostException e) {
			throw new IllegalStateException("Failed to get SocketAddress", e);
		}
	}
	
	/**
	 * 
	 */
	private ClientServer buildClientServer(InetSocketAddress address, int expectedClientContainerCount){
		try {
			Constructor<ClientServer> clCtr = ReflectionUtils.getInvocableConstructor(
					ClientServer.class.getPackage().getName() + ".ClientServerImpl", InetSocketAddress.class, int.class);
			ClientServer cs = clCtr.newInstance(address, expectedClientContainerCount);
			return cs;
		} 
		catch (Exception e) {
			throw new IllegalStateException("Failed to create ClientServer instance", e);
		}
	}
}