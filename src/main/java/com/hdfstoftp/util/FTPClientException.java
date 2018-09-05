package com.hdfstoftp.util;

import java.io.IOException;

/**
 * 
* @ClassName: FTPClientException 
* @Description: 自定义异常类，FTPClient异常 
* @author bianzexin 
* @date 2018年7月11日 下午1:56:23 
*
 */
public class FTPClientException extends Exception {

	private static final long serialVersionUID = 1L;

	public FTPClientException(String string, IOException e) {
		super(string,e);
	}
	public FTPClientException(String string) {
		super(string);
	}
	
}
