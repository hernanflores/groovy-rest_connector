package restconnector

import org.apache.http.util.EntityUtils 
import org.apache.http.conn.params.ConnRoutePNames 

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.http.auth.AuthScope 
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.conn.ClientConnectionManager 
import org.apache.http.impl.client.DefaultHttpClient 
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager

import org.apache.http.*
import org.apache.http.client.*
import org.apache.http.client.methods.* 
import org.apache.http.params.*
import org.apache.http.conn.scheme.*
import org.apache.http.conn.*

import org.apache.http.entity.StringEntity
import org.apache.http.conn.ssl.SSLSocketFactory

import groovy.util.ConfigObject 
import groovy.util.ConfigSlurper 

import net.sf.json.JSON;
import net.sf.json.groovy.JsonSlurper;

/**
 * 
 * @author hernanfloresleyes@gmail.com
 * 
 * <p>Implements the connection using <code>org.apache.http.client</code>.</p>
 */
class RESTConnector {       
	
	private HttpClient servClient
	private ConfigObject config
	private ClientConnectionManager ccm
	private String configPath
	
	private static RESTConnector INSTANCE = null;
	
	/**
	 * Constructor sincronizado para protegerse de posibles problemas  multi-hilo
	 otra prueba para evitar instanciación múltiple 
	 */
	private synchronized static void createInstance() {
		if (INSTANCE == null) { 
			INSTANCE = new RESTConnector();
		}
	}
	
	public static RESTConnector getInstance() {
		if (INSTANCE == null) createInstance();
		return INSTANCE;
	}
	
	
	/**
	 * Constructor por defecto. <b>No recomendado</b> ya que no prepara la configuracion de conexion.
	 * Si se usa este metodo para construir el Connector, configurar a mano ////
	 * @see setConfig
	 */
	private RESTConnector(){
		SchemeRegistry supportedSchemes = new SchemeRegistry();
		// Register the "http" and "https" protocol schemes, they are
		// required by the default operator to look up socket factories.
		supportedSchemes.register(new Scheme("http", 
				PlainSocketFactory.getSocketFactory(), 80));
		supportedSchemes.register(new Scheme("https", 
				SSLSocketFactory.getSocketFactory(), 443));
		
		HttpParams params = new BasicHttpParams();
		HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
		HttpProtocolParams.setContentCharset(params, "UTF-8");
		HttpProtocolParams.setUseExpectContinue(params, false)		
		
		println "Connection time-out: ${ConfigurationHolder.config.timeout.connection}" 
		
		config.timeout = [:]
		config.timeout.connection = ConfigurationHolder.config.timeout.connection
		config.timeout.socket = ConfigurationHolder.config.timeout.connection
		
		
		HttpConnectionParams.setConnectionTimeout(params, ConfigurationHolder.config.timeout.connection as Integer);
		HttpConnectionParams.setSoTimeout(params, ConfigurationHolder.config.timeout.socket as Integer)
		
		ccm = new ThreadSafeClientConnManager(params, supportedSchemes);
		
		params.setParameter(CoreProtocolPNames.HTTP_CONTENT_CHARSET,"UTF-8")
		params.setParameter(CoreProtocolPNames.HTTP_ELEMENT_CHARSET,"UTF-8")
		
		servClient = new DefaultHttpClient(ccm, params)
		
	}
	
	/**
	 * Retorna la configuracion actual del Connector. Para fines de control/debugging
	 * @see setConfig
	 * @return
	 */
	public ConfigObject getConfig(){
		return config
	}
	
	/**
	 * <b>Obligatorio</b> si se utiliza el constructor sin parametros.
	 * @param configObject de la forma "
	 * <code>
	 * service [ host:'http://www.google.com', proxy = [	enabled:true, host: '10.12.0.251', port: 3128, user: 'guest', password: 'mercadolibre' ]]
	 * </code>"
	 */
	public void setConfig(ConfigObject conf){
		this.config = conf
	}	
	
	/**
	 * Util en caso de querer configurar algun parametro de conexion a bajo nivel directamente en el client
	 * 
	 * @return la instancia del HttpClient para este RESTConnector
	 */
	public HttpClient getClient(){
		return servClient
	}
	
	private void setProxySettings(){
		String proxyHost = config.service.proxy.host
		Integer proxyPort = config.service.proxy.port
		String proxyUsername = config.service.proxy.user
		String proxyPassword = config.service.proxy.password
		
		servClient.getCredentialsProvider().setCredentials(
				new AuthScope(proxyHost, proxyPort), 
				new UsernamePasswordCredentials(proxyUsername, proxyPassword))
		
		HttpHost proxy = new HttpHost(proxyHost, proxyPort);
		servClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy)
		
		//		log.debug('Conexion por proxy con host ${proxyHost}, port ${proxyPort}, user ${proxyUsername}')
	}
	
	def connectWithRequestContent(String requestContent, HttpEntityEnclosingRequest httpMethod, String contentType){
		def ret
		
		StringEntity reqEntity = new StringEntity(requestContent);
		if(contentType.equals('xml')){
			reqEntity.setContentType("application/xml");
		}
		else{
			reqEntity.setContentType("application/json");
		}
		
		httpMethod.setEntity(reqEntity);
		
		if(config.service.proxy.enabled){
			setProxySettings()
		}
		
		try{
			
			HttpResponse httpResponse =  servClient.execute(httpMethod)
			def content = httpResponse
			def responseBody
			if(content.getEntity()){
				responseBody = new JsonSlurper().parse(new InputStreamReader(content.getEntity().getContent(), getCharset( httpResponse )));
			}else{
				responseBody = null
			}
			
			ret = [status: httpResponse.getStatusLine().getStatusCode(), data: responseBody]
		}
		catch(Exception e){
			ret = [status: 'FAIL', data: e.getMessage()]
		}
		return ret
	}
	
	/**
	 * Overwrites HTTPResponse.handleResponse to avoid exceptions getting a status higher
	 than 300
	 * @param response
	 * @return responseBody
	 */
	def handleResponse(response)
	throws HttpResponseException, IOException {
		StatusLine statusLine = response.getStatusLine();
		HttpEntity entity = response.getEntity();
		return entity == null ? null : EntityUtils.toString(entity);
	}
	
	def connectWithURI(HttpRequest httpMethod, String acceptType){
		
		def ret = [:]
		
		if(config.service.proxy.enabled){
			setProxySettings()
		}
		
		try{
			if(acceptType.equals('xml')){
				httpMethod.setHeader("Accept", "application/xml")
			}
			else{
				httpMethod.setHeader("Accept", "application/json")
			}
			//log.info "RESTConnector - antes de ejecutar operacion..."
			HttpResponse httpResponse =  servClient.execute(httpMethod)
			//log.info "RESTConnector - despues de ejecutar operacion..."
			ret.status = httpResponse.getStatusLine().getStatusCode()
			if(ret.status == 204){
				ret.data = "";
			}else{
				ret.data = new JsonSlurper().parse(new InputStreamReader(httpResponse.getEntity().getContent(), getCharset( httpResponse )));
			}
		}
		catch(Exception e){
			
			ret = [status: 'FAIL', data: e.getMessage()]
		}
		
		return ret
	}
	
	/**
	 * Conexion por post
	 * @param xmlRequest contenido del request. Puede ser XML u otro formato
	 * @return un map de la forma <code[status:'',data: contenidoDeResponse]></code> (status puede ser FAIL u OK)
	 */
	def execPost(String requestContent, String type){
		HttpPost httpPost = new HttpPost(config.service.host)
		
		return connectWithRequestContent(requestContent, httpPost,type)
	}
	
	/**
	 * Conexion por Get
	 * @param uri - La url del recurso a acceder
	 * @return un map de la forma <code[status:'',data: contenidoDeResponse]></code> (status puede ser FAIL u OK)
	 */
	def execGet(String uri, String acceptType){	
		//println "uri "+ uri
		HttpGet httpGet = new HttpGet(uri)
		
		return connectWithURI(httpGet, acceptType)
	}
	
	/**
	 * Conexion por put
	 * @param xmlRequest contenido del request. Puede ser XML u otro formato
	 * @return un map de la forma <code[status:'',data: contenidoDeResponse]></code> (status puede ser FAIL u OK)
	 */
	def execPut(String requestContent, String type){
		HttpPut httpPut = new HttpPut(config.service.host)
		
		return connectWithRequestContent(requestContent, httpPut, type)
	}	
	
	/**
	 * Conexion por Delete
	 * @param uri - La url del recurso a acceder
	 * @return un map de la forma <code[status:'',data: contenidoDeResponse]></code> (status puede ser FAIL u OK)
	 */
	def execDelete(String uri, String acceptType){
		HttpDelete httpDelete = new HttpDelete(uri)
		
		return connectWithURI(httpDelete, acceptType)
	}
	
	/**
	 * Release de los recursos. <b> Es una buena practica liberar las conexiones</b>
	 */
	//	public void killConn(){
	//		servClient.getConnectionManager().shutdown();   
	//	}
	
	private String getCharset( HttpResponse resp ) {
		try {
			NameValuePair charset = resp.getEntity().getContentType()
					.getElements()[0].getParameterByName("charset");
			
			if ( charset == null || charset.getValue().trim().equals("") ) {
				//println "Could not find charset in response, setting default charset"
				return Charset.defaultCharset().name();
			}
			
			return charset.getValue();
		}
		catch ( RuntimeException ex ) {
			//println "Could not parse content-type header in response, returning default charset"
			return Charset.defaultCharset().name();
		}
	}
}
