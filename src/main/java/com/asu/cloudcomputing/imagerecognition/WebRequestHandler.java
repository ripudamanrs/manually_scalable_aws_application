package com.asu.cloudcomputing.imagerecognition;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.model.GetQueueAttributesRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageResult;
import com.asu.cloudcomputing.util.StringConstantUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class WebRequestHandler implements HttpHandler {
	AmazonSQS sqs;
	AmazonEC2 ec2;
	String requestQueueUrl;
	Set<String> requested;
	Map<String, List<HttpExchange>> requestMap;
	Integer currentInstance;

	public WebRequestHandler(AmazonSQS sqs, AmazonEC2 ec2, String requestQueueUrl, Set<String> requested,
			Map<String, List<HttpExchange>> requestMap, Integer currentInstance) {
		super();
		this.sqs = sqs;
		this.ec2 = ec2;
		this.requestQueueUrl = requestQueueUrl;
		this.requested = requested;
		this.requestMap = requestMap;
		this.currentInstance = currentInstance;
	}

	void addToRequestQueue(String imageUrl) {
		SendMessageRequest sendMsgRq = new SendMessageRequest(requestQueueUrl, imageUrl);
		SendMessageResult sendMessageResult = sqs.sendMessage(sendMsgRq);
	}

	// counts number of instances running currently
	int countRunningInstances() {
		int instanceCounter = 0;
		DescribeInstancesResult describeInstancesRequest = ec2.describeInstances();
		List<Reservation> reservations = describeInstancesRequest.getReservations();
		// counting the number of instances
		for (Reservation reservation : reservations) {
			List<Instance> instances = reservation.getInstances();
			for (Instance instance : instances) {
				int i = instance.getState().getCode();
				// status pending or running.
				if (i == 0 || i == 16) {
					instanceCounter += 1;
				}
			}
		}
		return instanceCounter;
	}

	public void createInstance() {
		String instanceName = "app-instance-" + currentInstance;
		String userData = "#!/bin/bash\n" + "mkdir /home/ubuntu/.aws\n"
				+ "printf \"[default]\\noutput = json\\nregion = " + StringConstantUtil.REGION
				+ "\\n\" > home/ubuntu/.aws/config\n" + "printf \"[default]\\naws_secret_access_key = "
				+ StringConstantUtil.ACCESS_KEY + "\\naws_access_key_id = " + StringConstantUtil.ACCESS_KEY_ID
				+ "\\n\" > home/ubuntu/.aws/credentials\n"
				+ "su ubuntu -c \"java -cp /home/ubuntu/image-recognition.jar " + StringConstantUtil.APPSVR_CLASS + " "
				+ instanceName + "\"";
		RunInstancesRequest runInstance = new RunInstancesRequest();
		String startupScriptString = Base64.encodeBase64String(userData.getBytes());
		runInstance.withImageId(StringConstantUtil.AMI).withInstanceType(StringConstantUtil.INSTANCE_TYPE)
				.withMinCount(1).withMaxCount(1).withKeyName(StringConstantUtil.PUBLIC_KEY_NAME)
				.withSecurityGroups("default").withUserData(startupScriptString);
		RunInstancesResult result = ec2.runInstances(runInstance);
		// set instance name
		List<Tag> tags = new ArrayList<Tag>();
		Tag tag = new Tag();
		tag.setKey("Name");
		tag.setValue(instanceName);
		tags.add(tag);
		CreateTagsRequest ctr = new CreateTagsRequest();
		ctr.withTags(tags);
		List<Instance> resultInstanceList = result.getReservation().getInstances();
		ctr.withResources(resultInstanceList.get(0).getInstanceId());
		ec2.createTags(ctr);
	}

	public void handle(HttpExchange httpExchangeObj) throws IOException {
		String inputString = "";
		try {
			// shows received request
			inputString = httpExchangeObj.getRequestURI().toString();
			System.out.println("Request received" + inputString);
		} catch (Exception e) {
			e.printStackTrace();
		}
		// parse the for 1st instance of = request string to get imageURL
		String imageURL = inputString.substring(inputString.indexOf("=") + 1);
		if (imageURL.isEmpty()) {
			System.out.println("Incorrect URL format");
			OutputStream os = httpExchangeObj.getResponseBody();
			os.close();
			return;
		}

		if ((!requestMap.isEmpty()) && (requestMap.containsKey(imageURL))) {
			List<HttpExchange> httpHandlers = requestMap.get(imageURL);
			httpHandlers.add(httpExchangeObj);
		} else {
			// if not make a new entry for it and store imageurl, requester info
			// pair
			List<HttpExchange> httpHandlers = new ArrayList<HttpExchange>();
			httpHandlers.add(httpExchangeObj);
			requestMap.put(imageURL, httpHandlers);
		}

		// preventing multiple requestMap for same thing, if it has been
		// requested before
		if (!requested.contains(imageURL)) {
			addToRequestQueue(imageURL);
			requested.add(imageURL);
		}
		Integer appinstanceCount = countRunningInstances() - 1;
		Map<String, String> sqsAttr = sqs
				.getQueueAttributes(new GetQueueAttributesRequest(requestQueueUrl).withAttributeNames(
						Arrays.asList("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible")))
				.getAttributes();
		Integer sqsCount = Integer.valueOf(sqsAttr.get("ApproximateNumberOfMessages"))
				+ Integer.valueOf(sqsAttr.get("ApproximateNumberOfMessagesNotVisible"));
		if (appinstanceCount < StringConstantUtil.MAX_INSTANCE_COUNT && appinstanceCount < sqsCount) {
			createInstance();
			currentInstance++;
		}
	}
}
