package com.asu.cloudcomputing.imagerecognition;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

public class WebResponseHandler {

	HttpServer httpServer;
	AmazonSQS sqs;
	String responseQueueUrl;
	Set<String> requested;
	Map<String, List<HttpExchange>> requestMap;

	public WebResponseHandler(HttpServer httpServer, AmazonSQS sqs, String responseQueueUrl, Set<String> requested,
			Map<String, List<HttpExchange>> requestMap) {
		super();
		this.httpServer = httpServer;
		this.sqs = sqs;
		this.responseQueueUrl = responseQueueUrl;
		this.requested = requested;
		this.requestMap = requestMap;
	}

	void generateResponse(Integer ThreadCount) {
		int i = 1;
		while (i <= ThreadCount) {
			(new Thread(new responseQueueListener())).start();
			i++;
		}
	}

	class responseQueueListener implements Runnable {
		public void run() {
			while (true) {
				// looks if there is a response in the responseq every
				// millisecond
				ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest().withQueueUrl(responseQueueUrl)
						.withWaitTimeSeconds(2);
				receiveMessageRequest.setMaxNumberOfMessages(1);
				List<Message> msgs = sqs.receiveMessage(receiveMessageRequest).getMessages();
				// if there is a response
				if (msgs.size() > 0) {
					for (Message msg : msgs) {
						// break message to get parts
						String[] responseMessageSplit = msg.getBody().split(":::");
						String responseImageURL = responseMessageSplit[0];
						String responseAnswer = responseMessageSplit[2];
						requested.remove(responseImageURL);
						if (requestMap.containsKey(responseImageURL)) {
							List<HttpExchange> rqs = requestMap.get(responseImageURL);
							for (Iterator<HttpExchange> iterator = rqs.iterator(); iterator.hasNext();) {
								HttpExchange req = iterator.next();
								respond(responseAnswer, req);
								iterator.remove();
							}
							requestMap.remove(responseImageURL);
						}
						sqs.deleteMessage(responseQueueUrl, msg.getReceiptHandle());
					}
				}
			}
		}
	}

	static void respond(String imageName, HttpExchange httpHandler) {
		try {
			String response = "";
			Integer statusCode = 200;
			if (imageName.isEmpty()) {
				response = "Unable to Retrieve Image Name";
				statusCode = 404;
			} else {
				response = imageName;
			}
			httpHandler.sendResponseHeaders(statusCode, response.length());
			OutputStream os = httpHandler.getResponseBody();
			os.write(response.getBytes());
			os.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}