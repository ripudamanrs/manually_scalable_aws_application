package com.asu.cloudcomputing.imagerecognition;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.util.EC2MetadataUtils;
import com.asu.cloudcomputing.util.StringConstantUtil;

import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

public class AppTier {

	static AmazonEC2 ec2;
	static AmazonSQS sqs;
	static AmazonS3 s3;
	static String responseQueueUrl;
	static String requestQueueUrl;
	static String instanceName;

	public static void processMessage(Message msg) {
		int errCode = 200;
		try {
			ProcessBuilder builder = new ProcessBuilder(
					new String[] { "/bin/bash", "/home/ubuntu/image.sh", msg.getBody() });
			builder.redirectErrorStream(true);
			Process process = builder.start();
			errCode = process.waitFor();
		} catch (Exception e) {
			System.out.println("Error in Remote Python Script with error code : " + errCode);
		}

		File file = new File("/home/ubuntu/output.txt");
		String arr[] = new String[2];
		try {
			BufferedReader br = new BufferedReader(new FileReader(file));
			String st;
			while ((st = br.readLine()) != null) {
				System.out.println(st);
				arr = st.split(" \\(");
			}
			br.close();
		} catch (IOException e) {
			System.out.println("Error in reading Python Output");
		}
		String msgBody = msg.getBody();
		String imageFileName = msgBody.substring(msgBody.lastIndexOf('/') + 1, msgBody.length());
		String imageResult = arr[0].trim();
		ObjectMetadata metadata = new ObjectMetadata();
		metadata.setContentLength(imageResult.length());
		s3.putObject(new PutObjectRequest(StringConstantUtil.BUCKET_NAME, imageFileName,
				new ByteArrayInputStream(imageResult.getBytes()), metadata));
		addToResponseQueue(msgBody + ":::" + imageFileName + ":::" + imageResult);
		sqs.deleteMessage(requestQueueUrl, msg.getReceiptHandle());
	}

	public static void addToResponseQueue(String msg) {
		SendMessageRequest sendMsgRequest = new SendMessageRequest(responseQueueUrl, msg);
		sqs.sendMessage(sendMsgRequest);
	}

	public static void readMessage() {
		ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest().withQueueUrl(requestQueueUrl)
				.withWaitTimeSeconds(Integer.valueOf(StringConstantUtil.MAX_LONG_POLL));
		receiveMessageRequest.setMaxNumberOfMessages(1);
		List<Message> msgs = sqs.receiveMessage(receiveMessageRequest).getMessages();
		if (msgs.size() > 0) {
			processMessage(msgs.get(0));
			readMessage();
		} else {
			if ((countRunningInstances() > 2) && (!instanceName.equals("app-instance-0"))) {
				terminateInstance();
			} else {
				readMessage();
			}
		}
	}

	public static int countRunningInstances() {
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

	public static void terminateInstance() {
		String instanceId = EC2MetadataUtils.getInstanceId();
		TerminateInstancesRequest terminateRequest = new TerminateInstancesRequest().withInstanceIds(instanceId);
		ec2.terminateInstances(terminateRequest);
	}

	public static void main(String args[]) throws FileNotFoundException, UnsupportedEncodingException {
		instanceName = args[0];
		ec2 = AmazonEC2ClientBuilder.standard().withRegion(StringConstantUtil.REGION).build();
		sqs = AmazonSQSClientBuilder.standard().withRegion(StringConstantUtil.REGION).build();
		s3 = AmazonS3ClientBuilder.standard().withRegion(StringConstantUtil.REGION).build();

		responseQueueUrl = sqs.getQueueUrl(StringConstantUtil.RS_QUEUE).getQueueUrl();
		requestQueueUrl = sqs.getQueueUrl(StringConstantUtil.RQ_QUEUE).getQueueUrl();

		readMessage();

	}
}
