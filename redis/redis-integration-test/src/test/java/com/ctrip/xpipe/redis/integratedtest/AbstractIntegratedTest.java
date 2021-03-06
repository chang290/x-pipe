package com.ctrip.xpipe.redis.integratedtest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.exec.ExecuteException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ctrip.xpipe.api.cluster.LeaderElectorManager;
import com.ctrip.xpipe.cluster.DefaultLeaderElectorManager;
import com.ctrip.xpipe.redis.core.AbstractRedisTest;
import com.ctrip.xpipe.redis.core.config.MetaServerAddressAware;
import com.ctrip.xpipe.redis.core.entity.DcMeta;
import com.ctrip.xpipe.redis.core.entity.KeeperMeta;
import com.ctrip.xpipe.redis.core.entity.MetaServerMeta;
import com.ctrip.xpipe.redis.core.entity.RedisMeta;
import com.ctrip.xpipe.redis.core.entity.ZkServerMeta;
import com.ctrip.xpipe.redis.core.meta.MetaUtils;
import com.ctrip.xpipe.redis.core.metaserver.DefaultMetaServerLocator;
import com.ctrip.xpipe.redis.core.metaserver.MetaServerKeeperService;
import com.ctrip.xpipe.redis.keeper.RedisKeeperServer;
import com.ctrip.xpipe.redis.keeper.config.DefaultKeeperConfig;
import com.ctrip.xpipe.redis.keeper.config.KeeperConfig;
import com.ctrip.xpipe.redis.keeper.impl.DefaultRedisKeeperServer;
import com.ctrip.xpipe.redis.keeper.meta.DefaultMetaService;
import com.ctrip.xpipe.redis.keeper.monitor.KeepersMonitorManager;
import com.ctrip.xpipe.redis.keeper.monitor.impl.NoneKeepersMonitorManager;
import com.ctrip.xpipe.redis.meta.server.job.XSlaveofJob;
import com.ctrip.xpipe.zk.impl.DefaultZkClient;
import com.google.common.collect.Lists;

/**
 * @author wenchao.meng
 *
 *         Jun 13, 2016
 */
public abstract class AbstractIntegratedTest extends AbstractRedisTest {

	private static final String logDir = "target/applogs";

	private String integrated_test_config_file = "integrated-test.xml";

	private String integratedPropertiesFile = "integration.properties";

	private Properties integratedProperties;

	private String clusterId = "cluster1", shardId = "shard1";

	private int defaultTestMessageCount = 5000;

	private Set<RedisMeta> allRedisStarted = new HashSet<>();

	static {
		List<File> result = new LinkedList<>();
		cleanLog(new File(logDir), result);
		Logger logger = LoggerFactory.getLogger(AbstractIntegratedTest.class);
		logger.info("[cleanLog]{}", result);
	}

	@Before
	public void beforeAbstractIntegratedTest() throws Exception {

		doBeforeIntegratedTest();
		
		integratedProperties = new Properties();
		integratedProperties.load(com.ctrip.xpipe.utils.FileUtils.getFileInputStream(integratedPropertiesFile));

		initRegistry();
		startRegistry();
	}

	protected void doBeforeIntegratedTest() throws Exception {
		
	}

	protected static void cleanLog(File log, List<File> cleanFiles) {

		if (log.isFile()) {
			cleanFiles.add(log);
			FileUtils.deleteQuietly(log);
			return;
		}
		if (log.isDirectory()) {
			for (File file : log.listFiles()) {
				cleanLog(file, cleanFiles);
			}
		}
	}

	@Override
	protected String getXpipeMetaConfigFile() {
		return integrated_test_config_file;
	}

	public String getIntegrated_test_config_file() {
		return integrated_test_config_file;
	}

	protected void startZkServer(ZkServerMeta zkServerMeta) {

		String[] addresses = zkServerMeta.getAddress().split("\\s*,\\s*");
		if (addresses.length != 1) {
			throw new IllegalStateException("zk server test should only be one there!" + zkServerMeta.getAddress());
		}

		String[] parts = addresses[0].split(":");
		if (parts.length != 2) {
			throw new IllegalStateException("zk address wrong:" + addresses[0]);
		}
		int zkPort = Integer.parseInt(parts[1]);
		startZk(zkPort);
	}

	protected RedisKeeperServer startKeeper(KeeperMeta keeperMeta, MetaServerKeeperService metaService,
			LeaderElectorManager leaderElectorManager) throws Exception {

		return startKeeper(keeperMeta, getKeeperConfig(), metaService, leaderElectorManager);
	}

	protected KeeperConfig getKeeperConfig() {
		return new DefaultKeeperConfig();
	}

	protected RedisKeeperServer startKeeper(KeeperMeta keeperMeta, KeeperConfig keeperConfig, MetaServerKeeperService metaService,
			LeaderElectorManager leaderElectorManager) throws Exception {

		logger.info(remarkableMessage("[startKeeper]{}, {}"), keeperMeta);
		File baseDir = new File(getTestFileDir() + "/replication_store_" + keeperMeta.getPort());

		RedisKeeperServer redisKeeperServer = createRedisKeeperServer(keeperMeta, baseDir, keeperConfig, metaService, leaderElectorManager, new NoneKeepersMonitorManager());
		add(redisKeeperServer);
		return redisKeeperServer;
	}

	protected RedisKeeperServer createRedisKeeperServer(KeeperMeta keeperMeta, File baseDir, KeeperConfig keeperConfig,
			MetaServerKeeperService metaService, LeaderElectorManager leaderElectorManager, KeepersMonitorManager keeperMonitorManager) {

		return new DefaultRedisKeeperServer(keeperMeta, keeperConfig, baseDir, metaService, leaderElectorManager, keeperMonitorManager);
	}

	protected LeaderElectorManager createLeaderElectorManager(DcMeta dcMeta) throws Exception {

		DefaultZkClient zkClient = new DefaultZkClient();
		zkClient.setZkAddress(dcMeta.getZkServer().getAddress());
		zkClient.initialize();
		zkClient.start();
		add(zkClient);

		DefaultLeaderElectorManager leaderElectorManager = new DefaultLeaderElectorManager(zkClient);
		leaderElectorManager.initialize();
		leaderElectorManager.start();
		add(leaderElectorManager);
		return leaderElectorManager;
	}

	protected MetaServerKeeperService createMetaService(final List<MetaServerMeta> metaServerMetas) {

		DefaultMetaServerLocator metaServerLocator = new DefaultMetaServerLocator(new MetaServerAddressAware() {

			@Override
			public String getMetaServerUrl() {
				return String.format("http://%s:%d", "localhost", metaServerMetas.get(0).getPort());
			}
		});

		DefaultMetaService metaService = new DefaultMetaService();
		metaService.setConfig(new DefaultKeeperConfig());
		metaService.setMetaServerLocator(metaServerLocator);

		return metaService;
	}

	protected void startRedis(DcMeta dcMeta, RedisMeta redisMeta) throws ExecuteException, IOException {

		stopServerListeningPort(redisMeta.getPort());

		logger.info(remarkableMessage("[startRedis]{}"), redisMeta);

		File testDir = new File(getTestFileDir());
		File redisDir = new File(testDir, "redisconfig");
		File dataDir = new File(redisDir, "data");
		File logDir = new File(redisDir, "logs");

		FileUtils.forceMkdir(dataDir);
		FileUtils.forceMkdir(logDir);

		File file = createRedisConfigFile(dcMeta, redisMeta, redisDir, dataDir);
		executeScript("start_redis.sh", file.getAbsolutePath(),
				new File(logDir, String.format("%d.log", redisMeta.getPort())).getAbsolutePath());

		allRedisStarted.add(redisMeta);
	}

	protected File createRedisConfigFile(DcMeta dcMeta, RedisMeta redisMeta, File destDir, File dataDir)
			throws IOException {

		String conf = getRedisConfig(dcMeta, redisMeta, dataDir);

		File dstFile = new File(destDir, redisMeta.getPort() + ".conf");
		try (FileOutputStream fous = new FileOutputStream(dstFile)) {
			IOUtils.write(conf, fous);
		}
		return dstFile;
	}

	protected String getRedisConfig(DcMeta dcMeta, RedisMeta redisMeta, File dataDir) throws IOException {

		StringBuilder sb = new StringBuilder();

		try (InputStream ins_template = getClass().getClassLoader().getResourceAsStream(getRedisTemplate())) {
			int metaServerPort = dcMeta.getMetaServers().get(0).getPort();

			for (String line : IOUtils.readLines(ins_template)) {

				if (line.startsWith("#")) {
					sb.append(line);
					continue;
				}

				String[] confs = line.split("\\s+");
				if (confs.length < 2) {
					sb.append(line);
					continue;
				}

				String confKey = confs[0];
				if (confKey.equalsIgnoreCase("port")) {
					line = String.format("port %d", redisMeta.getPort());
				}
				if (confKey.equalsIgnoreCase("dir")) {
					line = String.format("dir %s", dataDir.getAbsolutePath());
				}
				if (confKey.equalsIgnoreCase("meta-server-url")) {
					line = String.format("meta-server-url http://localhost:%d/", metaServerPort);
				}
				if (confKey.equalsIgnoreCase("cluster-name")) {
					line = String.format("cluster-name %s", redisMeta.parent().parent().getId());
				}
				if (confKey.equalsIgnoreCase("shard-name")) {
					line = String.format("shard-name %s", redisMeta.parent().getId());
				}
				sb.append(line);
				sb.append("\r\n");
			}
		}
		
		if(diskless()){
			sb.append("repl-diskless-sync yes\r\n");
			sb.append("repl-diskless-sync-delay " +integratedProperties.getProperty("redis.repl.diskless.delay", "1") + "\r\n");
		}else{
			sb.append("repl-diskless-sync no\r\n");
		}
		

		endPrepareRedisConfig(redisMeta, sb);
		return sb.toString();
	}

	protected boolean diskless() {
		return Boolean.parseBoolean(integratedProperties.getProperty("redis.repl.diskless.sync", "false"));
	}

	protected void endPrepareRedisConfig(RedisMeta redisMeta, StringBuilder sb) {

	}

	protected void stopServerListeningPort(int listenPort) throws ExecuteException, IOException {

		logger.info("[stopServerListeningPort]{}", listenPort);
		executeScript("kill_server.sh", String.valueOf(listenPort));
	}

	public String getClusterId() {
		return clusterId;
	}

	public String getShardId() {
		return shardId;
	}

	protected abstract String getRedisTemplate();

	protected void sendMesssageToMasterAndTest(RedisMeta redisMaster, List<RedisMeta> slaves){
		sendMesssageToMasterAndTest(defaultTestMessageCount, redisMaster, slaves);
	}

	protected void sendMesssageToMasterAndTest(int messageCount, RedisMeta redisMaster, List<RedisMeta> slaves){

		sendMessageToMaster(redisMaster, messageCount);
		sleep(2000);
		assertRedisEquals(redisMaster, slaves);
	}

	protected void sendMessageToMaster(){
		sendMessageToMaster(getRedisMaster(), defaultTestMessageCount);
	}

	protected void sendMessageToMaster(RedisMeta redisMaster){
		sendMessageToMaster(redisMaster, defaultTestMessageCount);
	}

	protected void sendMessageToMaster(RedisMeta redisMaster, int messageCount){
		sendRandomMessage(redisMaster, messageCount);
	}

	protected void sendMesssageToMasterAndTest(List<RedisMeta> slaves){
		
		sendMesssageToMasterAndTest(defaultTestMessageCount, getRedisMaster(), Lists.newArrayList(slaves));
	}

	protected void sendMessageToMasterAndTestSlaveRedis(int messageCount) {
		sendMesssageToMasterAndTest(messageCount, getRedisMaster(), getRedisSlaves());
	}
	
	protected void sendMessageToMasterAndTestSlaveRedis() {
		sendMesssageToMasterAndTest(defaultTestMessageCount, getRedisMaster(), getRedisSlaves());
	}

	protected abstract List<RedisMeta> getRedisSlaves();

	protected List<RedisMeta> getAllRedisSlaves() {

		List<RedisMeta> result = new LinkedList<>();
		for (DcMeta dcMeta : getDcMetas()) {
			List<RedisMeta> slaves = getRedisSlaves(dcMeta.getId());
			Assert.assertTrue(slaves.size() >= 1);
			result.addAll(slaves);
		}
		Assert.assertTrue(result.size() >= 1);
		return result;
	}

	public RedisKeeperServer getRedisKeeperServerActive(String dc) {

		Map<String, RedisKeeperServer> redisKeeperServers = getRegistry().getComponents(RedisKeeperServer.class);

		for (RedisKeeperServer server : redisKeeperServers.values()) {
			String currentDc = server.getCurrentKeeperMeta().parent().parent().parent().getId();
			if (dc.equals(currentDc) && server.getRedisKeeperServerState().keeperState().isActive()) {
				return server;
			}
		}
		return null;
	}

	public RedisKeeperServer getRedisKeeperServer(KeeperMeta keeperMeta) {

		Map<String, RedisKeeperServer> redisKeeperServers = getRegistry().getComponents(RedisKeeperServer.class);

		for (RedisKeeperServer server : redisKeeperServers.values()) {
			KeeperMeta currentKeeperMeta = server.getCurrentKeeperMeta();
			if (MetaUtils.same(currentKeeperMeta, keeperMeta)) {
				return server;
			}
		}
		return null;
	}

	protected KeeperMeta getKeeperActive(String dc) {

		for (KeeperMeta keeperMeta : getDcKeepers(dc, getClusterId(), getShardId())) {
			if (keeperMeta.isActive()) {
				return keeperMeta;
			}
		}
		return null;
	}

	protected List<KeeperMeta> getKeepersBackup(String dc) {

		List<KeeperMeta> result = new LinkedList<>();
		for (KeeperMeta keeperMeta : getDcKeepers(dc, getClusterId(), getShardId())) {
			if (!keeperMeta.isActive()) {
				result.add(keeperMeta);
			}
		}
		return result;
	}

	protected void assertRedisEquals() {
		assertRedisEquals(getRedisMaster(), getRedisSlaves());
	}

	@After
	public void afterAbstractIntegratedTest() {

		for (RedisMeta redisMeta : allRedisStarted) {
			try {
				stopServerListeningPort(redisMeta.getPort());
			} catch (IOException e) {
				logger.error("[afterAbstractIntegratedTest][error stop redis]" + redisMeta, e);
			}
		}
	}

	protected void xslaveof(String masterIp, Integer masterPort, RedisMeta ... slaves) throws Exception {
		
		new XSlaveofJob(Lists.newArrayList(slaves), masterIp, masterPort, getXpipeNettyClientKeyedObjectPool(), scheduled).execute().sync();
	}

	protected void xslaveof(String masterIp, Integer masterPort, List<RedisMeta> slaves) throws Exception {
		
		new XSlaveofJob(slaves, masterIp, masterPort, getXpipeNettyClientKeyedObjectPool(), scheduled).execute().sync();
	}


	@Override
	protected boolean deleteTestDirAfterTest() {
		return false;
	}

}
