/*******************************************************************************
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*******************************************************************************/

package net.adoptopenjdk.test.jlm;

import net.adoptopenjdk.stf.StfException;
import net.adoptopenjdk.stf.environment.DirectoryRef;
import net.adoptopenjdk.stf.environment.FileRef;
import net.adoptopenjdk.stf.extensions.core.StfCoreExtension;
import net.adoptopenjdk.stf.plugin.interfaces.StfPluginInterface;
import net.adoptopenjdk.stf.processes.ExpectedOutcome;
import net.adoptopenjdk.stf.processes.StfProcess;
import net.adoptopenjdk.stf.processes.definitions.JavaProcessDefinition;
import net.adoptopenjdk.stf.processes.definitions.JavaProcessDefinition.JarId;
import net.adoptopenjdk.stf.processes.definitions.LoadTestProcessDefinition;
import net.adoptopenjdk.stf.runner.modes.HelpTextGenerator;
import net.adoptopenjdk.test.jlm.remote.ThreadProfiler;

import static net.adoptopenjdk.stf.extensions.core.StfCoreExtension.Echo.ECHO_OFF;
import static net.adoptopenjdk.stf.extensions.core.StfCoreExtension.Echo.ECHO_ON;

/**
 * STF automation to drive a java.lang.management system test scenario for Thread 
 * related beans where a 'client' JVM monitors a remote 'server' JVM via the server's 
 * platform MBeans with ssl security disabled. 
 * 
 * The test is divided into two parts. The first part drives the remote Thread  
 * test configuration for non-secure proxy connections. The second part drives the 
 * test configuration for non-secure server connections.   
 */
public class TestJlmRemoteThreadNoAuth implements StfPluginInterface {
	
	public void help(HelpTextGenerator help) throws StfException {
		help.outputSection("JLM Remote Thread test");
		help.outputText("These tests exercise java.lang.management APIs (Thread related beans) "
				+ "with MBeans for a remote JVM using non-secure proxy and server connections");
	}

	public void pluginInit(StfCoreExtension test) throws StfException {
	}

	public void setUp(StfCoreExtension test) throws StfException {
	}

	public void execute(StfCoreExtension test) throws StfException {
		
		/****************
		 *  Part 1) Drive the test configuration for non-secure proxy connection 
		 *****************/
		
		DirectoryRef resultsDir = test.env().getResultsDir();
		FileRef logFile	= resultsDir.childFile("thd_proxy.log");
		FileRef statsFile = resultsDir.childFile("thd_proxy.csv");
		FileRef dumpFile = resultsDir.childFile("javacore_thd_proxy.%Y%m%d.%H%M%S.%pid.%seq.txt,filter=java.lang.IllegalArgumentException");
		
		// Process definition for the server JVM
		String inventoryFile = "/openjdk.test.load/config/inventories/mix/mini-mix.xml";
		
		// setSuiteNumTests and setSuiteThreadCount need to be big enough to ensure the workload does not
		// end within the setTimeLimit time.
		LoadTestProcessDefinition serverLoadTestInvocation = test.createLoadTestSpecification()
			.addJvmOption("-Xmx256m")
			.addJvmOption("-Dcom.sun.management.jmxremote.port=1234")
			.addJvmOption("-Dcom.sun.management.jmxremote.authenticate=false")
			.addJvmOption("-Dcom.sun.management.jmxremote.ssl=false")
			.addPrereqJarToClasspath(JavaProcessDefinition.JarId.JUNIT)
			.addPrereqJarToClasspath(JavaProcessDefinition.JarId.HAMCREST)
			.addProjectToClasspath("openjdk.test.lang")  // For mini-mix inventory
			.addProjectToClasspath("openjdk.test.util")  // For mini-mix inventory
			.addProjectToClasspath("openjdk.test.math")  // For mini-mix inventory
			.setTimeLimit("30m")
			.setAbortAtFailureLimit(-1)
			.addSuite("mini-mix")
			.setSuiteNumTests(20000000)
			.setSuiteInventory(inventoryFile)
			.setSuiteThreadCount(30)
		   	.setSuiteRandomSelection();
		
		// Process definition for the client JVM using proxy connection	
		JavaProcessDefinition clientJavaInvocationProxy = test.createJavaProcessDefinition()
			.addJvmOption("-Xmx256m")
			.addJvmOptionIfIBMJava("-Xdump:java:events=throw,file=" + dumpFile.getSpec())
			.addProjectToClasspath("openjdk.test.jlm")
			.addPrereqJarToClasspath(JarId.JUNIT)
			.runClass(ThreadProfiler.class)
			.addArg("proxy")
			.addArg(logFile.getSpec())
			.addArg(statsFile.getSpec())
			.addArg("anon")
			.addArg("localhost")
			.addArg("1234");

		// Process definition for the client JVM using server connection
		JavaProcessDefinition clientJavaInvocationServer = test.createJavaProcessDefinition()
			.addJvmOption("-Xmx256m")
			.addJvmOptionIfIBMJava("-Xdump:java:events=throw,file=" + dumpFile.getSpec())
			.addProjectToClasspath("openjdk.test.jlm")
			.addPrereqJarToClasspath(JarId.JUNIT)
			.runClass(ThreadProfiler.class)
			.addArg("server")
			.addArg(logFile.getSpec())
			.addArg(statsFile.getSpec())
			.addArg("anon")
			.addArg("localhost")
			.addArg("1234");

		// Start the background server process
		StfProcess serverProxy = test.doRunBackgroundProcess("Running ThreadProfiler "
				+ "Proxy test Server Process", "LT1", ECHO_OFF, 
				ExpectedOutcome.neverCompletes(), serverLoadTestInvocation);
		
		// Start the background client process
		StfProcess clientProxy = test.doRunBackgroundProcess("Running the monitoring "
				+ "Client with proxy connection(without security)", 
				"CL1", ECHO_ON,  
				ExpectedOutcome.cleanRun().within("30m"), 
				clientJavaInvocationProxy);
		
		// Wait for the processes to complete
		test.doMonitorProcesses("Wait for processes to complete", serverProxy, clientProxy);
		test.doKillProcesses("Stop LT1 process", serverProxy);
		
		/****************
		 *  Part 2) Drive the test configuration for non-secure server connection 
		 *****************/
		logFile	= resultsDir.childFile("thd_server.log");
		statsFile = resultsDir.childFile("thd_server.csv");
		dumpFile = resultsDir.childFile("javacore_thd_server.%Y%m%d.%H%M%S.%pid.%seq.txt,filter=java.lang.IllegalArgumentException");
		
		// Start the background server process
		StfProcess serverS = test.doRunBackgroundProcess("Running ThreadProfiler "
				+ "Server test Server Process(without security)", "LT2", ECHO_OFF, 
				ExpectedOutcome.neverCompletes(), serverLoadTestInvocation);
		
		// Start the background client process
		StfProcess clientS = test.doRunBackgroundProcess("Running the Monitoring "
				+ "Client with server-connection(without security)", 
				"CL2", ECHO_ON,
				ExpectedOutcome.cleanRun().within("30m"), 
				clientJavaInvocationServer);
		
		// Wait for processes to complete
		test.doMonitorProcesses("Wait for processes to complete", serverS,clientS);
		test.doKillProcesses("Stop LT2 process", serverS);
	}

	public void tearDown(StfCoreExtension test) throws StfException {
	}
}