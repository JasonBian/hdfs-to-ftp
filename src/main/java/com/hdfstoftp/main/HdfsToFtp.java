package com.hdfstoftp.main;

import java.io.File;

/**
 * Licensed to the ctyun,this can be only used in ctyun company
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.pool.impl.contrib.FTPClientPool;
import org.apache.commons.pool.impl.contrib.FtpClientFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.mortbay.log.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hdfstoftp.config.Config;
import com.hdfstoftp.service.UploadFileTask;

/**
 * 
 * @ClassName: HdfsToFtp
 * @Description: 直接拷贝hdfs中的文件到ftp文件系统方法
 * @author bianzexin
 * @date 2018年7月11日 上午11:38:41
 *
 */
public class HdfsToFtp {

	/**
	 * 获取日志记录对象
	 */
	private static final Logger logger = LoggerFactory.getLogger("file");

	/**
	 * 静态退出方法
	 * 
	 * @param str
	 */
	static void printAndExit(String str) {
		logger.error(str);
		System.exit(1);
	}

	/**
	 * 入口函数
	 * 
	 * @param args
	 * @throws IOException
	 * @throws ParseException
	 */

	public static void main(String[] args) throws IOException, ParseException {
//		args = new String[] { "/Users/bianzexin/Downloads/Avro/csvtest", "/Users/bianzexin/Downloads/Avro/csvtest/", "-c /Users/bianzexin/Documents/workspace/hdfs-to-ftp/src/main/resources/conf/hdfs-to-ftp.properties"};
		try {
			logger.info("your input param is=" + Arrays.toString(args));
			Config config = new Config(args);
			logger.info("your config is =" + config.toString());
			copyFromHDFSToFTP(config);
		} catch (URISyntaxException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/** 
     * 递归创建远程服务器目录  
     * @param remote 远程服务器文件绝对路径  
     * @param ftpClient FTPClient 对象  
     * @return 目录创建是否成功  
     * @throws IOException  
     */  
	private static void createDirecroty(String remote, FTPClient ftpClient) throws IOException {
		String directory = remote.substring(0, remote.lastIndexOf("/") + 1);
		if (!directory.equalsIgnoreCase("/")
				&& !ftpClient.changeWorkingDirectory(new String(directory.getBytes("utf-8"), "utf-8"))) {
			// 如果远程目录不存在，则递归创建远程服务器目录
			int start = 0;
			int end = 0;
			start = directory.startsWith("/") ? 1 : 0;
			end = directory.indexOf("/", start);
			while (true) {
				String subDirectory = new String(remote.substring(start, end).getBytes("utf-8"), "utf-8");
				if (!ftpClient.changeWorkingDirectory(subDirectory)) {
					if (ftpClient.makeDirectory(subDirectory)) {
						ftpClient.changeWorkingDirectory(subDirectory);
					} else {
						logger.error("创建目录失败," + remote);
					}
				}
				start = end + 1;
				end = directory.indexOf("/", start);
				// 检查所有目录是否创建完毕
				if (end <= start) {
					break;
				}
			}
		}
	}

	/**
	 * 主拷贝方法
	 * 
	 * @param srcFS
	 *            文件系统
	 * @param src
	 *            源路径
	 * @param dst
	 *            目标路径
	 * @param queryStr
	 *            过滤字符串
	 * @param deleteSource
	 *            是否删除源文件
	 * @param overwrite
	 *            是否覆盖相同文件名文件
	 * @return boolean
	 * @throws Exception
	 */
	private static boolean copyFromHDFSToFTP(Config config) throws Exception {
		// 获取hdfs文件系统
		Configuration conf = new Configuration();
		FileSystem srcFS = FileSystem.get(conf);
		long start = System.currentTimeMillis();
		boolean isRename = config.isRenameUploaded();
		int retryTimes = config.getRetryTimes();
		// 获取目标路径
		String dstPath = config.getDestDir();
		Path src = new Path(config.getSouceDir());
		FileStatus fileStatus = srcFS.getFileStatus(src);
		String subDir = null;
		if (fileStatus.isDirectory()) {// 若是一个目录
			if (isRename) {// 若需要把上传成功的rename
				subDir = Config.RENAME_DIR;
				srcFS.mkdirs(new Path(fileStatus.getPath(), subDir));
			}
			int threadNum = config.getThreadNum();
			// 线程池
			ExecutorService threadPool = Executors.newFixedThreadPool(threadNum);
			// 获取ftp客户端
			FTPClientPool ftpPool = new FTPClientPool(threadNum, new FtpClientFactory(config.getFTPClientConfig()));
			FTPClient ftpClient = ftpPool.borrowObject();
			// 创建一个目标目录（如果乙存在默认不创建）
			createDirecroty(dstPath, ftpClient);
//			ftpClient.makeDirectory(dstPath);
			ftpPool.returnObject(ftpClient);
			// 列出源目录中的文件信息
			FileStatus contents[] = srcFS.listStatus(src);
			long beginFilter = 0;
			long endFileter = 0;

			if (config.getCommandLine().hasOption("d") || config.getCommandLine().hasOption("h")
					|| config.getCommandLine().hasOption("t")) {// 若不是以"["开头，则表示时间段
				beginFilter = System.currentTimeMillis();
				Long[] timeRange = parseTimeRange(config.getCommandLine());
				contents = getNewContents(timeRange, contents);
				endFileter = System.currentTimeMillis();
			}
			// 若有过滤条件
			if (config.getCommandLine().hasOption("r")) {// 若开头是"["，表示里面内容是正则表达式
				beginFilter = System.currentTimeMillis();
				contents = getFilterContents(config.getCommandLine().getOptionValue("r").trim(), contents);
				endFileter = System.currentTimeMillis();
			}
			logger.info("total file count:" + contents.length);
			Map<String, String> fileNameMap = null;
			long beginSkip = 0;
			long endSkip = 0;
			boolean overwrite = true;
			if (config.getCommandLine().hasOption("o")) {
				overwrite = "true".equals(config.getCommandLine().getOptionValue("o").trim());
			}
			if (!overwrite) {// 若不覆盖，获取源路径已存在的文件名列表
				beginSkip = System.currentTimeMillis();
				fileNameMap = getFileNameMap(dstPath, ftpPool);
				endSkip = System.currentTimeMillis();
			}
			int skiped = 0;
			
			List<Future<?>> futureList = new ArrayList<Future<?>>();
			Arrays.sort(contents, Collections.reverseOrder());
			Map<String, Path> successFileMap = new HashMap<String, Path>();
			for (int i = 0; i < contents.length; i++) {
				logger.info("start to process file:" + contents[i].getPath().getName());
				if (("_SUCCESS").equals(contents[i].getPath().getName())) {
					successFileMap.put("success", contents[i].getPath());
					continue;
				}
				if (!overwrite && fileNameMap.containsKey(contents[i].getPath().getName())) {
					// 跳过已存在的文件
					skiped++;
					logger.info("skiped filename:" + contents[i].getPath().getName());
					continue;
				}
				if (contents[i].isDirectory()) {
					continue;
				}
				// 用多线程的方式拷贝源文件到目标路径
				Future<?> future = threadPool.submit(new UploadFileTask(srcFS, contents[i].getPath(),
						new Path(dstPath, contents[i].getPath().getName()), ftpPool, false, isRename, subDir,
						retryTimes));
				futureList.add(future);
			}
			int transfered = 0;
			int failed = 0;
			for (Future<?> future : futureList) {
				Boolean computeResult = (Boolean) future.get();
				if (computeResult) {
					transfered++;
					if (transfered % 50 == 0 || transfered == contents.length) {
						logger.info("have transfered:" + transfered + " files");
					}
				} else {
					failed++;
					logger.error("failed transter:" + failed + " files");
				}
			}
			if (successFileMap!= null && successFileMap.containsKey("success")) {
				Path successPath = successFileMap.get("success");
				Future<?> future = threadPool.submit(new UploadFileTask(srcFS, successPath,
						new Path(dstPath, successPath.getName()), ftpPool, false, isRename, subDir,
						retryTimes));
				Boolean result = (Boolean) future.get();
				if (result) {
					logger.info("have transfered _SUCCESS file");
				} else {
					logger.info("transfered _SUCCESS file error");
				}
			}
			// 关闭线程池
			threadPool.shutdown();
			// 关闭FTPCient连接池
			ftpPool.close();
			// ****************
			logger.info("filter time:" + (endFileter - beginFilter) + " ms");
			if (!overwrite) {
				logger.info("skip time:" + (endSkip - beginSkip) + " ms");
			}
			logger.info("total file count:" + contents.length);
			logger.info("total transtered: " + transfered + ",total failed:" + failed + ",total skiped:" + skiped);

		} else {// 若是文件，直接上传
			FtpClientFactory facotry = new FtpClientFactory(config.getFTPClientConfig());
			FTPClient ftpClient = null;
			InputStream in = null;
			boolean reply = false;
			try {
				Path path = fileStatus.getPath();
				in = srcFS.open(path);
				ftpClient = facotry.makeObject();
				createDirecroty(dstPath, ftpClient);
				int tryedTimes = 0;
				while (!reply && (tryedTimes <= retryTimes)) {
					reply = ftpClient.storeFile(dstPath + File.separator + path.getName(), in);
					if (tryedTimes > 0) {
						Thread.sleep(100 * tryedTimes);
						logger.warn("retryed times:" + tryedTimes + " return Info:" + ftpClient.getReplyString());
					}
					tryedTimes++;
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				in.close();
				facotry.destroyObject(ftpClient);
			}
		}
		long end = System.currentTimeMillis();
		logger.info("finished transfer,total time:" + (end - start) / 1000 + "s");
		return true;
	}

	public static Long[] parseTimeRange(CommandLine commandLine) throws ParseException, java.text.ParseException {

		SimpleDateFormat sdf = null;
		Long beginTime = null;
		Long endTime = null;

		if (commandLine.hasOption("d")) {
			sdf = new SimpleDateFormat(Config.FORMAT_DATE);
			Date beginDate = sdf.parse(commandLine.getOptionValue("d").trim());
			beginTime = beginDate.getTime();
			Date endDate = DateUtils.addDays(beginDate, 1);
			endTime = endDate.getTime();
		} else if (commandLine.hasOption("h")) {
			sdf = new SimpleDateFormat(Config.FORMAT_HOUR);
			Date beginDate = sdf.parse(commandLine.getOptionValue("h").trim());
			beginTime = beginDate.getTime();
			Date endDate = DateUtils.addHours(beginDate, 1);
			endTime = endDate.getTime();

		} else if (commandLine.hasOption("t")) {
			sdf = new SimpleDateFormat(Config.FORMAT_SS);
			Date beginDate = sdf.parse(commandLine.getOptionValue("t").trim());
			beginTime = beginDate.getTime();
		}

		Long[] timeRange = { beginTime, endTime };
		return timeRange;
	}

	/**
	 * 获得指定路径下的文件名Map
	 * 
	 * @param path
	 * @return Map<String, String>
	 * @throws Exception
	 * @throws IllegalStateException
	 * @throws NoSuchElementException
	 */
	public static Map<String, String> getFileNameMap(String path, FTPClientPool ftpPool)
			throws NoSuchElementException, IllegalStateException, Exception {
		Map<String, String> fileNameMap = new HashMap<String, String>();
		FTPClient client = ftpPool.borrowObject();
		FTPFile[] files = client.listFiles(path);
		ftpPool.returnObject(client);
		for (FTPFile file : files) {
			if (file.isFile()) {
				fileNameMap.put(file.getName(), "");
			}
		}
		return fileNameMap;
	}

	/**
	 * 按文件名内容过滤
	 * 
	 * @param queryStr
	 * @param contents
	 * @return FileStatus[]
	 */
	public static FileStatus[] getFilterContents(String reg, FileStatus[] contents) {

		Pattern pattern = Pattern.compile(reg);
		List<FileStatus> statusList = new ArrayList<FileStatus>();
		for (FileStatus status : contents) {
			if (!status.isDirectory()) {
				String fileName = status.getPath().getName();
				Matcher matcher = pattern.matcher(fileName);
				if (matcher.matches()) {
					statusList.add(status);
				}
			}
		}
		return statusList.toArray(new FileStatus[statusList.size()]);
	}

	/**
	 * filter files
	 * 
	 * @param timeRange
	 * @param fileStatus
	 * @return FileStatus[]
	 */
	public static FileStatus[] getNewContents(Long[] timeRange, FileStatus[] fileStatus) {
		List<FileStatus> statusList = new ArrayList<FileStatus>();
		for (int i = 0; i < fileStatus.length; i++) {
			long modificationTime = fileStatus[i].getModificationTime();
			if (timeRange[1] != null) {
				if (timeRange[0] < modificationTime && modificationTime < timeRange[1]) {
					statusList.add(fileStatus[i]);
				}
			} else {
				if (timeRange[0] < modificationTime) {
					statusList.add(fileStatus[i]);
				}
			}
		}
		return statusList.toArray(new FileStatus[statusList.size()]);
	}

}