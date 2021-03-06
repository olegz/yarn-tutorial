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
package oz.hadoop.yarn.api.net;

import java.net.InetSocketAddress;

/**
 * @author Oleg Zhurakousky
 *
 */
public interface SocketHandler {
	
	/**
	 * 
	 * @return
	 */
	InetSocketAddress start();
	
	/**
	 * 
	 * @param force
	 */
	void stop(boolean force);
	
	/**
	 * 
	 * @return
	 */
	boolean isRunning();
	
	/**
	 * Blocking method which returns only if this 
	 * ApplicationContainerClient has been shut down
	 */
	public abstract void awaitShutdown();
}
