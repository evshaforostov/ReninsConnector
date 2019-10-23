package ru.zcts.logic.manager.connector;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlSchema;


import org.apache.log4j.Logger;
import org.datacontract.schemas._2004._07.renins_core_base_model.ConfirmPaymentResponse;
import org.datacontract.schemas._2004._07.renins_core_base_model.PrintPolicyResponse;


import com.sun.xml.bind.marshaller.CharacterEscapeHandler;
import travel_click.common.utils.IOUtils;

public class ReninsConnector {
        
	private static Logger log = Logger.getLogger(ReninsConnector.class);
	
	private static final String DEFAULT_HOST = "test1.services.renins.com";
        
    private static final String XMLNSPATTERN = "<?xml[\\W\\S]*(xmlns:(.*)=\"[^\"]*cbtc[^\"]*\")([^>]*)>([\\W\\S]*)</[^<]:return";
    private static final String XMLENDTAGREPLACER = "(<(([\\w]*:[\\w]*)\\s[^>]*)/>)";
        
	private CharacterEscapeHandler handler;
        
    private String path;
    private String host;
    
    private ReninsConnector(String host, String path) {
             this.host = host;
             this.path = path;
    }
    
    public ReninsConnector(String path) {
             this(DEFAULT_HOST, path);
    }
    
    public ReninsConnector() {
             host = DEFAULT_HOST;
    }
    
    
    private String sendRequest(String request) throws IOException {
        String response;
        OutputStream outputStream = null;
        InputStream inputStream = null;
        Socket socket = null;
        try {
             InetAddress address = InetAddress.getByName(host);
             SocketFactory factory = SocketFactory.getDefault();
             socket = factory.createSocket(address, 80);
             outputStream = socket.getOutputStream();
             inputStream = socket.getInputStream();
             outputStream.write(request.getBytes("UTF8"));
             outputStream.flush();
             response = IOUtils.convertStreamToString(inputStream);
        } finally {
             try {
                 socket.close();
                 outputStream.close();
                 inputStream.close();
             } catch (NullPointerException ignore) {
             }
        }
        return response;
    }
    
    public String doRequest(String order, String soapAction) throws IOException {
        String request = createRequest(order, soapAction);
        log.info("Renins request: \n" + request);
        String response = sendRequest(request);
        log.info("Renins response: \n" + response);

        if (response.contains("<soap:Fault>")) {
             int start = response.indexOf("<faultstring>") + 13;
             int end = response.indexOf("</faultstring>");
        }

        response = cropResponseByBody(response);
        return response;
    }
    
    @SuppressWarnings("unchecked")
        public <T> T doRequest(Object reqObject, Class<T> responseClass) throws Exception {
             String[] splt = responseClass.getName().split("Response");
                String soapAction = splt[0].substring(splt[0].lastIndexOf(".")+1);
        JAXBContext context = JAXBContext.newInstance(((T) reqObject).getClass());
        Marshaller marshaller = context.createMarshaller();
        StringWriter writer = new StringWriter();
        marshaller.marshal((T) reqObject, writer);
        String xmlRequest = writer.toString().replace("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>", "");
        String responseString = null;
        try {
             responseString = doRequest(xmlRequest, soapAction);
             return (T) unmarshal(responseClass, responseString);
        } catch (Exception e) {
                 log.info(e.getMessage());
                 //может содержать недопустимые в xml символы
                 return parseResponse(responseString, responseClass);
        }

    }
    
    
    @SuppressWarnings("unchecked")
        private <T> T parseResponse(String response, Class<T> responseClass) {
             Object resp = new Object();
             if (responseClass.getName().equals("org.datacontract.schemas._2004._07.renins_core_base_model.ConfirmPaymentResponse")) {
                      
                      ConfirmPaymentResponse confirmPaymentResponse = new ConfirmPaymentResponse();
                      resp = confirmPaymentResponse;
             } else if (responseClass.getName().equals("org.datacontract.schemas._2004._07.renins_core_base_model.PrintPolicyResponse")) {
                      int startOfURL = response.indexOf("<a:URL>") + 7;
                      int endOfURL = response.indexOf("</a:URL>");
                      String url = response.substring(startOfURL, endOfURL);
                      int startOfSuccess = response.indexOf("<a:Success>") + 11;
                      int endOfSuccess = response.indexOf("</a:Success>");
                      String success = response.substring(startOfSuccess, endOfSuccess);
                      PrintPolicyResponse printPolicyResponse = new PrintPolicyResponse();
                      printPolicyResponse.setURL(url);
                      printPolicyResponse.setSuccess(new Boolean(success));
                      resp = printPolicyResponse;
             }
             
             return (T) resp;
    }
    
    
    public <T> T unmarshal(Class<T> clazz, String reader) throws JAXBException {
             Unmarshaller unmarshaller = JAXBContext.newInstance(clazz.getPackage().getName()).createUnmarshaller();
             StringReader sr = castTransportAnswer(clazz, reader);
        return clazz.cast(unmarshaller.unmarshal(sr));
    }
    
    private <T> StringReader castTransportAnswer(Class<T> clazz, String transportAnswer) {
             String reader = new String(transportAnswer);
             
             Matcher mendtag = Pattern.compile(XMLENDTAGREPLACER).matcher(transportAnswer);
                 while (mendtag.find()) {
                          transportAnswer = transportAnswer.replace(mendtag.group(1), "<" + mendtag.group(2) + "></" + mendtag.group(3) + ">");
                 }
             
                 Matcher m = Pattern.compile(XMLNSPATTERN).matcher(transportAnswer);
                 while(m.find()) {
                          reader = new String("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                            "\n<" + m.group(2) + ":" + clazz.getAnnotation(XmlRootElement.class).name() + " " + m.group(1) + ">" +
                                            "\n<" + m.group(2) + ":return " + m.group(3) + ">" +
                                            m.group(4) + "" +
                                            "\n</" + m.group(2) + ":return>" +
                                            "\n</" + m.group(2) + ":" + clazz.getAnnotation(XmlRootElement.class).name() + ">");
                          
                          if (reader.toString().contains("transportservice")) {
                                   m = Pattern.compile(XMLNSPATTERN.replace("cbtc", "(cbtc)*")).matcher(transportAnswer);
                                   m.find();
                                   reader = new String("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                                                     "\n<cns:" + clazz.getAnnotation(XmlRootElement.class).name() + " xmlns:cns=\""+clazz.getPackage().getAnnotation(XmlSchema.class).namespace()+"\">" +
                                                     "\n<cns:return " + m.group(1) + m.group(4) + ">" +
                                                     m.group(5) + "" +
                                                     "\n</cns:return>" +
                                                     "\n</cns:" + clazz.getAnnotation(XmlRootElement.class).name() + ">");
                          }
                 }
                 return new StringReader(reader);
    }

    private String createRequest(String order, String soapAction) {
        String soapBody = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ns2=\"http://renins.com/ns/TravelISS\" xmlns:ns3=\"http://schemas.datacontract.org/2004/07/Renins.Core.Base.Model.Travel\">\r\n" +
                 "    <soapenv:Header/>\r\n" +
                 "    <soapenv:Body>" +
                 order +
                 "</soapenv:Body>\r\n" +
                 "</soapenv:Envelope>\r\n";

        StringBuilder soapRequest = new StringBuilder("");
        try {
             soapRequest.append("POST ").append(path).append(" HTTP/1.0\r\n")
                      .append("Host: ").append(host).append("\r\n")
                      .append("Content-Type: text/xml;charset=\"UTF-8\"\r\n")
                      .append("SOAPAction: \"http://renins.com/ns/TravelISS/IPolicyIntegrationService/" + soapAction + "\"\r\n")
                      .append("Content-Length: ").append(soapBody.getBytes("UTF8").length).append("\r\n\r\n")
                      .append(soapBody);
        } catch (UnsupportedEncodingException e) {
             log.error(e.getMessage(), e);
        }

        return soapRequest.toString();
    }
    
    private String cropResponseByBody(String response) {
        if (response.contains("<soap:Fault>")) return response;
        int startIndex = response.indexOf("<s:Body>");
        int endIndex = response.indexOf("</s:Body>");
        return startIndex + endIndex > 0 ? "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + response.substring(startIndex + 8, endIndex) : null;
    }

}