package com.asu.cloudcomputing.imagerecognition;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.asu.cloudcomputing.util.StringConstantUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class WebTier {

	static Map<String, List<HttpExchange>> requestMap;
	static AmazonSQS sqs;
	static AmazonS3 s3;
	static AmazonEC2 ec2;
	static String requestQueueUrl;
	static String responseQueueUrl;
	static Set<String> requested;
	static Integer currentInstance = 1;

	static HttpServer startServer(WebRequestHandler webRequestHandler) throws IOException {
		HttpServer httpServer = HttpServer.create(new InetSocketAddress(StringConstantUtil.HTTP_PORT), 128);
		httpServer.createContext(StringConstantUtil.APP_NAME, webRequestHandler);
		httpServer.setExecutor(Executors.newFixedThreadPool(StringConstantUtil.REQ_THREAD_COUNT)); 
		httpServer.start();

		return httpServer;
	}

	static void initializeSQSQueuesAndS3() {

		CreateQueueRequest QueueRequestObj = new CreateQueueRequest(StringConstantUtil.RQ_QUEUE);
		QueueRequestObj.addAttributesEntry("ReceiveMessageWaitTimeSeconds", StringConstantUtil.MAX_LONG_POLL);

		CreateQueueRequest QueueResponseObj = new CreateQueueRequest(StringConstantUtil.RS_QUEUE);
		QueueResponseObj.addAttributesEntry("ReceiveMessageWaitTimeSeconds", StringConstantUtil.MAX_LONG_POLL);

		CreateQueueResult requestQueue = sqs.createQueue(QueueRequestObj);
		CreateQueueResult responseQueue = sqs.createQueue(QueueResponseObj);

		requestQueueUrl = requestQueue.getQueueUrl();
		responseQueueUrl = responseQueue.getQueueUrl();

		if (s3.doesBucketExistV2(StringConstantUtil.BUCKET_NAME)) {
			System.out.println("Bucket exists : No need to create");
		} else {
			s3.createBucket(StringConstantUtil.BUCKET_NAME);
		}
	}

	public static void main(String[] args) throws Exception {

		ec2 = AmazonEC2ClientBuilder.standard().withRegion(StringConstantUtil.REGION).build();
		sqs = AmazonSQSClientBuilder.standard().withRegion(StringConstantUtil.REGION).build();
		s3 = AmazonS3ClientBuilder.standard().withRegion(StringConstantUtil.REGION).build();

		requested = new HashSet<String>();
		requestMap = new HashMap<String, List<HttpExchange>>();

		System.out.println("AWS Objects Created");

		initializeSQSQueuesAndS3();

		System.out.println("AWS Queues Created");

		WebRequestHandler webRequestHandler = new WebRequestHandler(sqs, ec2, requestQueueUrl, requested, requestMap,
				currentInstance);
		try {
			HttpServer httpServer = startServer(webRequestHandler);
			System.out.println("AWS Server Started");
			WebResponseHandler webResponseHandler = new WebResponseHandler(httpServer, sqs, responseQueueUrl, requested,
					requestMap);
			webResponseHandler.generateResponse(StringConstantUtil.RES_THREAD_COUNT);
			System.out.println("AWS Response Gathered");
		} catch (IOException ioe) {
			ioe.printStackTrace();
			System.out.println("Unable to Start Server");
		}
	}

}