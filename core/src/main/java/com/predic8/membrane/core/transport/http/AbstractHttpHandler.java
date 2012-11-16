/* Copyright 2009, 2012 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package com.predic8.membrane.core.transport.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;

import com.predic8.membrane.core.Constants;
import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.http.MimeType;
import com.predic8.membrane.core.http.Request;
import com.predic8.membrane.core.http.Response;
import com.predic8.membrane.core.interceptor.InterceptorFlowController;
import com.predic8.membrane.core.transport.Transport;
import com.predic8.membrane.core.util.ContentTypeDetector;
import com.predic8.membrane.core.util.EndOfStreamException;
import com.predic8.membrane.core.util.HttpUtil;

public abstract class AbstractHttpHandler  {

	private static Log log = LogFactory.getLog(AbstractHttpHandler.class.getName());

	protected Exchange exchange;
	protected Request srcReq;
	private static final InterceptorFlowController flowController = new InterceptorFlowController();
		
	private final Transport transport;
	
	public AbstractHttpHandler(Transport transport) {
		this.transport = transport;
	}

	public Transport getTransport() {
		return transport;
	}

	/**
	 * Only use for HTTP/1.0 requests. (see {@link HttpClient})
	 */
	public abstract void shutdownInput() throws IOException;
	public abstract InetAddress getLocalAddress();
	public abstract InetAddress getRemoteAddress();
	public abstract int getLocalPort();

	
	protected void invokeHandlers() throws IOException, EndOfStreamException, AbortException, NoMoreRequestsException, EOFWhileReadingFirstLineException {
		try {
			flowController.invokeHandlers(exchange, transport.getInterceptors());
			if (exchange.getResponse() == null)
				throw new AbortException("No response was generated by the interceptor chain.");
		} catch (Exception e) {
			if (exchange.getResponse() == null)
				exchange.setResponse(generateErrorResponse(e));
			
			if (e instanceof IOException)
				throw (IOException)e;
			if (e instanceof EndOfStreamException)
				throw (EndOfStreamException)e;
			if (e instanceof AbortException)
				throw (AbortException)e; // TODO: migrate catch logic into this method
			if (e instanceof NoMoreRequestsException)
				throw (NoMoreRequestsException)e;
			if (e instanceof EOFWhileReadingFirstLineException)
				throw (EOFWhileReadingFirstLineException)e;
			log.warn("An exception occured while handling a request: ", e);
		}
	}

	private Response generateErrorResponse(Exception e) {
		String msg;
		boolean printStackTrace = transport.isPrintStackTrace();
		if (printStackTrace) {
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			msg = sw.toString();
		} else {
			msg = e.toString();
		}
		String comment = "Stack traces can be " + (printStackTrace ? "dis" : "en") + "abled by setting the "+
				"@printStackTrace attribute on <a href=\"http://membrane-soa.org/esb-doc/current/configuration/reference/transport.htm\">transport</a>. " +
				"More details might be found in the log.";
		
		Response error = null;
		switch (ContentTypeDetector.detect(exchange.getRequest()).getEffectiveContentType()) {
		case XML:
			error = Response.interalServerError().
				header(HttpUtil.createHeaders(MimeType.TEXT_XML_UTF8)).
				body(("<error><message>" + 
						StringEscapeUtils.escapeXml(msg) + 
						"</message><comment>" + 
						StringEscapeUtils.escapeXml(comment) + 
						"</comment></error>").getBytes(Constants.UTF_8_CHARSET)).
				build();
			break;
		case JSON:
	        ByteArrayOutputStream baos = new ByteArrayOutputStream();
	        try {
	        	JsonGenerator jg = new JsonFactory().createJsonGenerator(baos);
	        	jg.writeStartObject();
	        	jg.writeFieldName("error");
	        	jg.writeString(msg);
	        	jg.writeFieldName("comment");
	        	jg.writeString(comment);
	        	jg.close();
	        } catch (Exception f) {
	        	log.error("Error generating JSON error response", f);
	        }

			error = Response.interalServerError().
					header(HttpUtil.createHeaders(MimeType.JSON)).
					body(baos.toByteArray()).
					build();
			break;
		case SOAP:
			error = Response.interalServerError().
				header(HttpUtil.createHeaders(MimeType.TEXT_XML_UTF8)).
				body(HttpUtil.getFaultSOAPBody("Internal Server Error", msg + " " + comment).getBytes(Constants.UTF_8_CHARSET)).
				build();
			break;
		case UNKNOWN:
			error = HttpUtil.createHTMLErrorResponse(msg, comment);
			break;
		}
		return error;
	}

	/**
	 * @return whether the {@link #getLocalPort()} of the handler has to match
	 *         the rule's local port for the rule to apply.
	 */
	public boolean isMatchLocalPort() {
		return true;
	}

	public String getContextPath(Exchange exc) {
		return "";
	}

}
