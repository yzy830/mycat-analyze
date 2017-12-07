/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat;

import io.mycat.backend.BackendConnection;
import io.mycat.backend.datasource.PhysicalDBPool;
import io.mycat.buffer.BufferPool;
import io.mycat.buffer.DirectByteBufferPool;
import io.mycat.cache.CacheService;
import io.mycat.config.MycatConfig;
import io.mycat.config.classloader.DynaClassLoader;
import io.mycat.config.model.SystemConfig;
import io.mycat.config.table.structure.MySQLTableStructureDetector;
import io.mycat.manager.ManagerConnectionFactory;
import io.mycat.memory.MyCatMemory;
import io.mycat.net.AIOAcceptor;
import io.mycat.net.AIOConnector;
import io.mycat.net.NIOAcceptor;
import io.mycat.net.NIOConnector;
import io.mycat.net.NIOProcessor;
import io.mycat.net.NIOReactorPool;
import io.mycat.net.SocketAcceptor;
import io.mycat.net.SocketConnector;
import io.mycat.route.MyCATSequnceProcessor;
import io.mycat.route.RouteService;
import io.mycat.route.factory.RouteStrategyFactory;
import io.mycat.server.ServerConnectionFactory;
import io.mycat.server.interceptor.SQLInterceptor;
import io.mycat.server.interceptor.impl.GlobalTableUtil;
import io.mycat.statistic.SQLRecorder;
import io.mycat.statistic.stat.SqlResultSizeRecorder;
import io.mycat.statistic.stat.UserStat;
import io.mycat.statistic.stat.UserStatAnalyzer;
import io.mycat.util.ExecutorUtil;
import io.mycat.util.NameableExecutor;
import io.mycat.util.TimeUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.AsynchronousChannelGroup;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * @author mycat
 */
public class MycatServer {
	
	public static final String NAME = "MyCat";
	private static final long LOG_WATCH_DELAY = 60000L;
	private static final long TIME_UPDATE_PERIOD = 20L;
	private static final long DEFAULT_SQL_STAT_RECYCLE_PERIOD = 5 * 1000L;
	private static final long DEFAULT_OLD_CONNECTION_CLEAR_PERIOD = 5 * 1000L;
	
	private static final MycatServer INSTANCE = new MycatServer();
	private static final Logger LOGGER = LoggerFactory.getLogger("MycatServer");
	/**
	 * SQL路由器
	 */
	private final RouteService routerService;
	private final CacheService cacheService;
	/**
	 * 读取src/main/conf/dnindex.properties的配置
	 */
	private Properties dnIndexProperties;
	
	//AIO连接群组
	private AsynchronousChannelGroup[] asyncChannelGroups;
	private volatile int channelIndex = 0;

	//全局序列号
	private final MyCATSequnceProcessor sequnceProcessor = new MyCATSequnceProcessor();
	private final DynaClassLoader catletClassLoader;
	private final SQLInterceptor sqlInterceptor;
	private volatile int nextProcessor;
	
	// System Buffer Pool Instance
	private BufferPool bufferPool;
	private boolean aio = false;

	//XA事务全局ID生成
	private final AtomicLong xaIDInc = new AtomicLong();


	/**
	 * Mycat 内存管理类
	 */
	private MyCatMemory myCatMemory = null;

	public static final MycatServer getInstance() {
		return INSTANCE;
	}

	/**
	 * 配置
	 */
	private final MycatConfig config;
	/**
	 * 定时器线程池(单线程)，用于触发定时任务
	 */
	private final ScheduledExecutorService scheduler;
	/**
	 * SQL记录器，作用不确定
	 */
	private final SQLRecorder sqlRecorder;
	private final AtomicBoolean isOnline;
	private final long startupTime;
	private NIOProcessor[] processors;
	private SocketConnector connector;
	private NameableExecutor businessExecutor;
	private NameableExecutor timerExecutor;
	/**
	 * 用于异步处理businessExecutor的任务执行结果。
	 */
	private ListeningExecutorService listeningExecutorService;

	private  long totalNetWorkBufferSize = 0;
	private MycatServer() {
		
		//读取文件配置
		this.config = new MycatConfig();
		
		//定时线程池，单线程线程池
		scheduler = Executors.newSingleThreadScheduledExecutor();
		
		//SQL记录器
		this.sqlRecorder = new SQLRecorder(config.getSystem().getSqlRecordCount());
		
		/**
		 * 是否在线，MyCat manager中有命令控制
		 * | offline | Change MyCat status to OFF |
		 * | online | Change MyCat status to ON |
		 */
		this.isOnline = new AtomicBoolean(true);
		
		//缓存服务初始化
		cacheService = new CacheService();
		
		//路由计算初始化
		routerService = new RouteService(cacheService);
		
		// load datanode active index from properties
		dnIndexProperties = loadDnIndexProps();
		try {
			//SQL解析器
			sqlInterceptor = (SQLInterceptor) Class.forName(
					config.getSystem().getSqlInterceptor()).newInstance();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		
		//catlet加载器
		catletClassLoader = new DynaClassLoader(SystemConfig.getHomePath()
				+ File.separator + "catlet", config.getSystem().getCatletClassCheckSeconds());
		
		//记录启动时间
		this.startupTime = TimeUtil.currentTimeMillis();
	}

	public long getTotalNetWorkBufferSize() {
		return totalNetWorkBufferSize;
	}
	
	public BufferPool getBufferPool() {
		return bufferPool;
	}

	public NameableExecutor getTimerExecutor() {
		return timerExecutor;
	}

	public DynaClassLoader getCatletClassLoader() {
		return catletClassLoader;
	}

	public MyCATSequnceProcessor getSequnceProcessor() {
		return sequnceProcessor;
	}

	public SQLInterceptor getSqlInterceptor() {
		return sqlInterceptor;
	}

	public String genXATXID() {
		long seq = this.xaIDInc.incrementAndGet();
		if (seq < 0) {
			synchronized (xaIDInc) {
				if ( xaIDInc.get() < 0 ) {
					xaIDInc.set(0);
				}
				seq = xaIDInc.incrementAndGet();
			}
		}
		return "'Mycat." + this.getConfig().getSystem().getMycatNodeId() + "." + seq + "'";
	}

	public MyCatMemory getMyCatMemory() {
		return myCatMemory;
	}

	/**
	 * get next AsynchronousChannel ,first is exclude if multi
	 * AsynchronousChannelGroups
	 *
	 * @return
	 */
	public AsynchronousChannelGroup getNextAsyncChannelGroup() {
		if (asyncChannelGroups.length == 1) {
			return asyncChannelGroups[0];
		} else {
			int index = (++channelIndex) % asyncChannelGroups.length;
			if (index == 0) {
				++channelIndex;
				return asyncChannelGroups[1];
			} else {
				return asyncChannelGroups[index];
			}

		}
	}

	public MycatConfig getConfig() {
		return config;
	}

	public void beforeStart() {
		String home = SystemConfig.getHomePath();


		//ZkConfig.instance().initZk();
	}

	/**
	 * @throws IOException
	 */
	/**
	 * @throws IOException
	 */
	public void startup() throws IOException {

		SystemConfig system = config.getSystem();
		int processorCount = system.getProcessors();

		// server startup
		LOGGER.info("===============================================");
		LOGGER.info(NAME + " is ready to startup ...");
		String inf = "Startup processors ...,total processors:"
				+ system.getProcessors() + ",aio thread pool size:"
				+ system.getProcessorExecutor()
				+ "    \r\n each process allocated socket buffer pool "
				+ " bytes ,a page size:"
				+ system.getBufferPoolPageSize()
				+ "  a page's chunk number(PageSize/ChunkSize) is:"
				+ (system.getBufferPoolPageSize()
				  /system.getBufferPoolChunkSize())
				+ "  buffer page's number is:"
				+ system.getBufferPoolPageNumber();
		LOGGER.info(inf);
		LOGGER.info("sysconfig params:" + system.toString());

		// startup manager
		ManagerConnectionFactory mf = new ManagerConnectionFactory();
		ServerConnectionFactory sf = new ServerConnectionFactory();
		SocketAcceptor manager = null;
		SocketAcceptor server = null;
		aio = (system.getUsingAIO() == 1);

		// startup processors
		int threadPoolSize = system.getProcessorExecutor();
		processors = new NIOProcessor[processorCount];
		// a page size，默认情况下，是2048KB
		int bufferPoolPageSize = system.getBufferPoolPageSize();
		// total page number，默认情况下，是20 * processorCount
		short bufferPoolPageNumber = system.getBufferPoolPageNumber();
		//minimum allocation unit，默认情况下是4KB
		short bufferPoolChunkSize = system.getBufferPoolChunkSize();
		
		int socketBufferLocalPercent = system.getProcessorBufferLocalPercent();
		int bufferPoolType = system.getProcessorBufferPoolType();

		switch (bufferPoolType){
			case 0:
			    // 默认情况下bufferPool全部使用DirectBuffer实现。每个DirectBuffer的大小为bufferPoolPageSize，一共有bufferPoolPageNumber个
				bufferPool = new DirectByteBufferPool(bufferPoolPageSize,bufferPoolChunkSize,
					bufferPoolPageNumber,system.getFrontSocketSoRcvbuf());
			

				totalNetWorkBufferSize = bufferPoolPageSize*bufferPoolPageNumber;
				break;
			case 1:
				/**
				 * todo 对应权威指南修改：
				 *
				 * bytebufferarena由6个bytebufferlist组成，这六个list有减少内存碎片的机制
				 * 每个bytebufferlist由多个bytebufferchunk组成，每个list也有减少内存碎片的机制
				 * 每个bytebufferchunk由多个page组成，平衡二叉树管理内存使用状态，计算灵活
				 * 设置的pagesize对应bytebufferarena里面的每个bytebufferlist的每个bytebufferchunk的buffer长度
				 * bufferPoolChunkSize对应每个bytebufferchunk的每个page的长度
				 * bufferPoolPageNumber对应每个bytebufferlist有多少个bytebufferchunk
				 */

				totalNetWorkBufferSize = 6*bufferPoolPageSize * bufferPoolPageNumber;
				break;
			default:
				bufferPool = new DirectByteBufferPool(bufferPoolPageSize,bufferPoolChunkSize,
					bufferPoolPageNumber,system.getFrontSocketSoRcvbuf());;
				totalNetWorkBufferSize = bufferPoolPageSize*bufferPoolPageNumber;
		}
		
			/**
		 * Off Heap For Merge/Order/Group/Limit 初始化
		 */
		if(system.getUseOffHeapForMerge() == 1){
			try {
				myCatMemory = new MyCatMemory(system,totalNetWorkBufferSize);
			} catch (NoSuchFieldException e) {
				LOGGER .error("NoSuchFieldException",e);
			} catch (IllegalAccessException e) {
				LOGGER.error("Error",e);
			}
		}
		businessExecutor = ExecutorUtil.create("BusinessExecutor",
				threadPoolSize);
		timerExecutor = ExecutorUtil.create("Timer", system.getTimerExecutor());
		// yzy: 用于异步执行回调businessExecutor的回调结果
		listeningExecutorService = MoreExecutors.listeningDecorator(businessExecutor);

		for (int i = 0; i < processors.length; i++) {
		    // 现在buffer pool和businessExecutor都是用的统一一个，并没有按核隔离
			processors[i] = new NIOProcessor("Processor" + i, bufferPool,
					businessExecutor);
		}

		if (aio) {
			LOGGER.info("using aio network handler ");
			asyncChannelGroups = new AsynchronousChannelGroup[processorCount];
			// startup connector
			connector = new AIOConnector();
			for (int i = 0; i < processors.length; i++) {
				asyncChannelGroups[i] = AsynchronousChannelGroup.withFixedThreadPool(processorCount,
					new ThreadFactory() {
						private int inx = 1;
						@Override
						public Thread newThread(Runnable r) {
							Thread th = new Thread(r);
							//TODO
							th.setName(DirectByteBufferPool.LOCAL_BUF_THREAD_PREX + "AIO" + (inx++));
							LOGGER.info("created new AIO thread "+ th.getName());
							return th;
						}
					}
				);
			}
			manager = new AIOAcceptor(NAME + "Manager", system.getBindIp(),
					system.getManagerPort(), mf, this.asyncChannelGroups[0]);

			// startup server

			server = new AIOAcceptor(NAME + "Server", system.getBindIp(),
					system.getServerPort(), sf, this.asyncChannelGroups[0]);

		} else {
			LOGGER.info("using nio network handler ");
			
			NIOReactorPool reactorPool = new NIOReactorPool(
					DirectByteBufferPool.LOCAL_BUF_THREAD_PREX + "NIOREACTOR",
					processors.length);
			// 创建后端连接处理器，负责后端连接的创建，一旦连接成功，交给NIOReactor处理
			connector = new NIOConnector(DirectByteBufferPool.LOCAL_BUF_THREAD_PREX + "NIOConnector", reactorPool);
			((NIOConnector) connector).start();

			manager = new NIOAcceptor(DirectByteBufferPool.LOCAL_BUF_THREAD_PREX + NAME
					+ "Manager", system.getBindIp(), system.getManagerPort(), mf, reactorPool);

			server = new NIOAcceptor(DirectByteBufferPool.LOCAL_BUF_THREAD_PREX + NAME
					+ "Server", system.getBindIp(), system.getServerPort(), sf, reactorPool);
		}
		// manager start
		manager.start();
		LOGGER.info(manager.getName() + " is started and listening on " + manager.getPort());
		server.start();
		
		// server started
		LOGGER.info(server.getName() + " is started and listening on " + server.getPort());
		
		LOGGER.info("===============================================");
		
		// init datahost
		Map<String, PhysicalDBPool> dataHosts = config.getDataHosts();
		LOGGER.info("Initialize dataHost ...");
		for (PhysicalDBPool node : dataHosts.values()) {
			String index = dnIndexProperties.getProperty(node.getHostName(),"0");
			if (!"0".equals(index)) {
				LOGGER.info("init datahost: " + node.getHostName() + "  to use datasource index:" + index);
			}
			node.init(Integer.parseInt(index));
			node.startHeartbeat();
		}
		
		long dataNodeIldeCheckPeriod = system.getDataNodeIdleCheckPeriod();

		scheduler.scheduleAtFixedRate(updateTime(), 0L, TIME_UPDATE_PERIOD,TimeUnit.MILLISECONDS);
		scheduler.scheduleAtFixedRate(processorCheck(), 0L, system.getProcessorCheckPeriod(),TimeUnit.MILLISECONDS);
		scheduler.scheduleAtFixedRate(dataNodeConHeartBeatCheck(dataNodeIldeCheckPeriod), 0L, dataNodeIldeCheckPeriod,TimeUnit.MILLISECONDS);
		scheduler.scheduleAtFixedRate(dataNodeHeartbeat(), 0L, system.getDataNodeHeartbeatPeriod(),TimeUnit.MILLISECONDS);
		scheduler.scheduleAtFixedRate(dataSourceOldConsClear(), 0L, DEFAULT_OLD_CONNECTION_CLEAR_PERIOD, TimeUnit.MILLISECONDS);
		scheduler.schedule(catletClassClear(), 30000,TimeUnit.MILLISECONDS);
       
		if(system.getCheckTableConsistency()==1) {
            scheduler.scheduleAtFixedRate(tableStructureCheck(), 0L, system.getCheckTableConsistencyPeriod(), TimeUnit.MILLISECONDS);
        }
		
		if(system.getUseSqlStat()==1) {
			scheduler.scheduleAtFixedRate(recycleSqlStat(), 0L, DEFAULT_SQL_STAT_RECYCLE_PERIOD, TimeUnit.MILLISECONDS);
		}
		
		if(system.getUseGlobleTableCheck() == 1){	// 全局表一致性检测是否开启
			scheduler.scheduleAtFixedRate(glableTableConsistencyCheck(), 0L, system.getGlableTableCheckPeriod(), TimeUnit.MILLISECONDS);
		}
		
		//定期清理结果集排行榜，控制拒绝策略
		scheduler.scheduleAtFixedRate(resultSetMapClear(),0L,  system.getClearBigSqLResultSetMapMs(),TimeUnit.MILLISECONDS);
		
		RouteStrategyFactory.init();
//        new Thread(tableStructureCheck()).start();
	}

	private Runnable catletClassClear() {
		return new Runnable() {
			@Override
			public void run() {
				try {
					catletClassLoader.clearUnUsedClass();
				} catch (Exception e) {
					LOGGER.warn("catletClassClear err " + e);
				}
			};
		};
	}
	

	/**
	 * 清理 reload @@config_all 后，老的 connection 连接
	 * @return
	 */
	private Runnable dataSourceOldConsClear() {
		return new Runnable() {
			@Override
			public void run() {				
				timerExecutor.execute(new Runnable() {
					@Override
					public void run() {		
						
						long sqlTimeout = MycatServer.getInstance().getConfig().getSystem().getSqlExecuteTimeout() * 1000L;
						
						//根据 lastTime 确认事务的执行， 超过 sqlExecuteTimeout 阀值 close connection 
						long currentTime = TimeUtil.currentTimeMillis();
						Iterator<BackendConnection> iter = NIOProcessor.backends_old.iterator();
						while( iter.hasNext() ) {
							BackendConnection con = iter.next();							
							long lastTime = con.getLastTime();						
							if ( currentTime - lastTime > sqlTimeout ) {								
								con.close("clear old backend connection ...");
								iter.remove();									
							}
						}
					}				
				});				
			};
		};
	}
	
	
	/**
	 * 在bufferpool使用率大于使用率阈值时不清理
	 * 在bufferpool使用率小于使用率阈值时清理大结果集清单内容
	 * 
	 */
	private Runnable resultSetMapClear() {
		return new Runnable() {
			@Override
			public void run() {
				try {
					BufferPool bufferPool=getBufferPool();
				    long bufferSize=bufferPool.size();
				    long bufferCapacity=bufferPool.capacity();
					long bufferUsagePercent=(bufferCapacity-bufferSize)*100/bufferCapacity;
					if(bufferUsagePercent<config.getSystem().getBufferUsagePercent()){
						Map<String, UserStat> map =UserStatAnalyzer.getInstance().getUserStatMap();
						Set<String> userSet=config.getUsers().keySet();
					for (String user : userSet) {
						UserStat userStat = map.get(user);
						if(userStat!=null){
							SqlResultSizeRecorder recorder=userStat.getSqlResultSizeRecorder();
							//System.out.println(recorder.getSqlResultSet().size());
							recorder.clearSqlResultSet();
						}
					}
					}
				} catch (Exception e) {
					LOGGER.warn("resultSetMapClear err " + e);
				}
			};
		};
	}

	private Properties loadDnIndexProps() {
		Properties prop = new Properties();
		File file = new File(SystemConfig.getHomePath(), "conf" + File.separator + "dnindex.properties");
		if (!file.exists()) {
			return prop;
		}
		FileInputStream filein = null;
		try {
			filein = new FileInputStream(file);
			prop.load(filein);
		} catch (Exception e) {
			LOGGER.warn("load DataNodeIndex err:" + e);
		} finally {
			if (filein != null) {
				try {
					filein.close();
				} catch (IOException e) {
				}
			}
		}
		return prop;
	}

	/**
	 * save cur datanode index to properties file
	 *
	 * @param
	 * @param curIndex
	 */
	public synchronized void saveDataHostIndex(String dataHost, int curIndex) {

		File file = new File(SystemConfig.getHomePath(), "conf" + File.separator + "dnindex.properties");
		FileOutputStream fileOut = null;
		try {
			String oldIndex = dnIndexProperties.getProperty(dataHost);
			String newIndex = String.valueOf(curIndex);
			if (newIndex.equals(oldIndex)) {
				return;
			}
			
			dnIndexProperties.setProperty(dataHost, newIndex);
			LOGGER.info("save DataHost index  " + dataHost + " cur index " + curIndex);

			File parent = file.getParentFile();
			if (parent != null && !parent.exists()) {
				parent.mkdirs();
			}

			fileOut = new FileOutputStream(file);
			dnIndexProperties.store(fileOut, "update");
		} catch (Exception e) {
			LOGGER.warn("saveDataNodeIndex err:", e);
		} finally {
			if (fileOut != null) {
				try {
					fileOut.close();
				} catch (IOException e) {
				}
			}
		}

	}

	public RouteService getRouterService() {
		return routerService;
	}

	public CacheService getCacheService() {
		return cacheService;
	}

	public NameableExecutor getBusinessExecutor() {
		return businessExecutor;
	}

	public RouteService getRouterservice() {
		return routerService;
	}

	public NIOProcessor nextProcessor() {
		int i = ++nextProcessor;
		if (i >= processors.length) {
			i = nextProcessor = 0;
		}
		return processors[i];
	}

	public NIOProcessor[] getProcessors() {
		return processors;
	}

	public SocketConnector getConnector() {
		return connector;
	}

	public SQLRecorder getSqlRecorder() {
		return sqlRecorder;
	}

	public long getStartupTime() {
		return startupTime;
	}

	public boolean isOnline() {
		return isOnline.get();
	}

	public void offline() {
		isOnline.set(false);
	}

	public void online() {
		isOnline.set(true);
	}

	// 系统时间定时更新任务
	private Runnable updateTime() {
		return new Runnable() {
			@Override
			public void run() {
				TimeUtil.update();
			}
		};
	}

	// 处理器定时检查任务
	private Runnable processorCheck() {
		return new Runnable() {
			@Override
			public void run() {
				timerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							for (NIOProcessor p : processors) {
								p.checkBackendCons();
							}
						} catch (Exception e) {
							LOGGER.warn("checkBackendCons caught err:" + e);
						}

					}
				});
				timerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						try {
							for (NIOProcessor p : processors) {
								p.checkFrontCons();
							}
						} catch (Exception e) {
							LOGGER.warn("checkFrontCons caught err:" + e);
						}
					}
				});
			}
		};
	}

	// 数据节点定时连接空闲超时检查任务
	private Runnable dataNodeConHeartBeatCheck(final long heartPeriod) {
		return new Runnable() {
			@Override
			public void run() {
				timerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						
						Map<String, PhysicalDBPool> nodes = config.getDataHosts();
						for (PhysicalDBPool node : nodes.values()) {
							node.heartbeatCheck(heartPeriod);
						}
						
						/*
						Map<String, PhysicalDBPool> _nodes = config.getBackupDataHosts();
						if (_nodes != null) {
							for (PhysicalDBPool node : _nodes.values()) {
								node.heartbeatCheck(heartPeriod);
							}
						}*/
					}
				});
			}
		};
	}

	// 数据节点定时心跳任务
	private Runnable dataNodeHeartbeat() {
		return new Runnable() {
			@Override
			public void run() {
				timerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						Map<String, PhysicalDBPool> nodes = config.getDataHosts();
						for (PhysicalDBPool node : nodes.values()) {
							node.doHeartbeat();
						}
					}
				});
			}
		};
	}

	//定时清理保存SqlStat中的数据
	private Runnable recycleSqlStat(){
		return new Runnable() {
			@Override
			public void run() {
				Map<String, UserStat> statMap = UserStatAnalyzer.getInstance().getUserStatMap();
				for (UserStat userStat : statMap.values()) {
					userStat.getSqlLastStat().recycle();
					userStat.getSqlRecorder().recycle();
					userStat.getSqlHigh().recycle();
					userStat.getSqlLargeRowStat().recycle();
				}
			}
		};
	}

	//定时检查不同分片表结构一致性
	private Runnable tableStructureCheck(){
		return new MySQLTableStructureDetector();
	}
	
	//  全局表一致性检查任务
	private Runnable glableTableConsistencyCheck() {
		return new Runnable() {
			@Override
			public void run() {
				timerExecutor.execute(new Runnable() {
					@Override
					public void run() {
						GlobalTableUtil.consistencyCheck();
					}
				});
			}
		};
	}

	public boolean isAIO() {
		return aio;
	}

	public ListeningExecutorService getListeningExecutorService() {
		return listeningExecutorService;
	}
}
