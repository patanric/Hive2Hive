package org.hive2hive.core.processes.common.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Random;

import net.tomp2p.peers.PeerAddress;

import org.hive2hive.core.H2HJUnitTest;
import org.hive2hive.core.H2HTestData;
import org.hive2hive.core.exceptions.NoPeerConnectionException;
import org.hive2hive.core.exceptions.NoSessionException;
import org.hive2hive.core.exceptions.SendFailedException;
import org.hive2hive.core.model.BaseNetworkContent;
import org.hive2hive.core.network.NetworkManager;
import org.hive2hive.core.network.data.parameters.Parameters;
import org.hive2hive.core.network.messages.MessageReplyHandler;
import org.hive2hive.core.network.messages.direct.response.ResponseMessage;
import org.hive2hive.core.network.messages.direct.testmessages.TestDirectMessage;
import org.hive2hive.core.network.messages.direct.testmessages.TestDirectMessageWithReply;
import org.hive2hive.core.utils.H2HWaiter;
import org.hive2hive.core.utils.NetworkTestUtil;
import org.hive2hive.core.utils.TestExecutionUtil;
import org.hive2hive.core.utils.helper.DenyingMessageReplyHandler;
import org.hive2hive.processframework.exceptions.InvalidProcessStateException;
import org.hive2hive.processframework.exceptions.ProcessExecutionException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for the {@link BaseDirectMessageProcessStep} class. Checks if the process step successes when message
 * successfully arrives and if the process step fails (triggers rollback) when the sending of a message fails.
 * 
 * @author Seppi
 */
public class BaseDirectMessageProcessStepTest extends H2HJUnitTest {

	private static ArrayList<NetworkManager> network;
	private static final int networkSize = 10;
	private static Random random = new Random();

	@BeforeClass
	public static void initTest() throws Exception {
		testClass = BaseDirectMessageProcessStepTest.class;
		beforeClass();
		network = NetworkTestUtil.createNetwork(networkSize);
		NetworkTestUtil.setSameSession(network);
	}

	/**
	 * Sends a direct asynchronous message through a process step. This test checks if the process step
	 * successes
	 * when the message arrives at the right target node (given through {@link PeerAddress}). This
	 * is verified by locally storing and looking for the sent test data at the receiving node.
	 * 
	 * @throws IOException
	 * @throws ClassNotFoundException
	 * @throws NoPeerConnectionException
	 */
	@Test
	public void baseDirectMessageProcessStepTestOnSuccess() throws ClassNotFoundException, IOException,
			NoPeerConnectionException {
		// select two random nodes
		NetworkManager nodeA = network.get(random.nextInt(network.size() / 2));
		final NetworkManager nodeB = network.get(random.nextInt(network.size() / 2) + network.size() / 2);
		// generate random data and content key
		String data = randomString();
		String contentKey = randomString();
		Parameters parameters = new Parameters().setLocationKey(nodeB.getNodeId()).setContentKey(contentKey);

		// check if selected location is empty
		assertNull(nodeA.getDataManager().get(parameters));

		// create a message with target node B
		final TestDirectMessage message = new TestDirectMessage(nodeB.getNodeId(), nodeB.getConnection().getPeerDHT()
				.peerAddress(), contentKey, new H2HTestData(data), false);

		// initialize the process and the one and only step to test
		BaseDirectMessageProcessStep step = new BaseDirectMessageProcessStep(nodeA.getMessageManager()) {
			@Override
			public void handleResponseMessage(ResponseMessage responseMessage) {
				Assert.fail("Should be not used.");
			}

			@Override
			protected Void doExecute() throws InvalidProcessStateException {
				try {
					sendDirect(message, getPublicKey(nodeB));
				} catch (SendFailedException e) {
					Assert.fail();
				}
				return null;
			}
		};
		TestExecutionUtil.executeProcessTillSucceded(step);

		// wait till message gets handled
		H2HWaiter w = new H2HWaiter(10);
		BaseNetworkContent content = null;
		do {
			w.tickASecond();
			content = nodeB.getDataManager().get(parameters);
		} while (content == null);

		// verify that data arrived
		assertEquals(data, ((H2HTestData) content).getTestString());
	}

	/**
	 * Sends an asynchronous message through a process step. This test checks if the process step fails
	 * when the message gets denied at the target node (node which is responsible for the given key).
	 * 
	 * @throws NoPeerConnectionException
	 * @throws InvalidProcessStateException
	 */
	@Test
	public void baseDirectMessageProcessStepTestOnFailure() throws NoPeerConnectionException, InvalidProcessStateException {
		// select two random nodes
		NetworkManager nodeA = network.get(random.nextInt(network.size() / 2));
		final NetworkManager nodeB = network.remove(random.nextInt(network.size() / 2) + network.size() / 2);
		try {
			// generate random data and content key
			String data = randomString();
			String contentKey = randomString();
			Parameters parameters = new Parameters().setLocationKey(nodeB.getNodeId()).setContentKey(contentKey);

			// check if selected location is empty
			assertNull(nodeA.getDataManager().get(parameters));

			// assign a denying message handler at target node
			nodeB.getConnection().getPeerDHT().peer().objectDataReply(new DenyingMessageReplyHandler());

			// create a message with target node B
			final TestDirectMessage message = new TestDirectMessage(nodeB.getNodeId(), nodeB.getConnection().getPeerDHT()
					.peerAddress(), contentKey, new H2HTestData(data), false);

			// initialize the process and the one and only step to test
			BaseDirectMessageProcessStep step = new BaseDirectMessageProcessStep(nodeA.getMessageManager()) {
				@Override
				public void handleResponseMessage(ResponseMessage responseMessage) {
					Assert.fail("Should be not used.");
				}

				@Override
				protected Void doExecute() throws InvalidProcessStateException, ProcessExecutionException {
					try {
						sendDirect(message, getPublicKey(nodeB));
						Assert.fail();
					} catch (SendFailedException ex) {
						throw new ProcessExecutionException(this, ex, "Expected behavior.");
					}
					return null;
				}
			};
			TestExecutionUtil.executeProcessTillFailed(step);

			// check if selected location is still empty
			assertNull(nodeB.getDataManager().get(parameters));
		} finally {
			nodeB.getConnection().getPeerDHT().peer()
					.objectDataReply(new MessageReplyHandler(nodeB, nodeB.getDataManager().getEncryption()));
		}
	}

	/**
	 * Sends an asynchronous request message through a process step. This test checks if the process step
	 * successes when receiving node responds to a request message.
	 * 
	 * @throws IOException
	 * @throws ClassNotFoundException
	 * @throws NoPeerConnectionException
	 */
	@Test
	public void baseDirectMessageProcessStepTestWithARequestMessage() throws ClassNotFoundException, IOException,
			NoPeerConnectionException {
		// select two random nodes
		final NetworkManager nodeA = network.get(random.nextInt(network.size() / 2));
		final NetworkManager nodeB = network.get(random.nextInt(network.size() / 2) + network.size() / 2);
		// generate a random content key
		final String contentKey = randomString();
		final Parameters parametersA = new Parameters().setLocationKey(nodeA.getNodeId()).setContentKey(contentKey);
		final Parameters parametersB = new Parameters().setLocationKey(nodeB.getNodeId()).setContentKey(contentKey);

		// check if selected locations are empty
		assertNull(nodeB.getDataManager().get(parametersA));
		assertNull(nodeA.getDataManager().get(parametersB));

		// create a message with target node B
		final TestDirectMessageWithReply message = new TestDirectMessageWithReply(nodeB.getConnection().getPeerDHT()
				.peerAddress(), contentKey);

		// initialize the process and the one and only step to test
		BaseDirectMessageProcessStep step = new BaseDirectMessageProcessStep(nodeA.getMessageManager()) {
			@Override
			public void handleResponseMessage(ResponseMessage responseMessage) {
				// locally store on requesting node received data
				String receivedSecret = (String) responseMessage.getContent();
				try {
					nodeA.getDataManager().putUnblocked(parametersA.setNetworkContent(new H2HTestData(receivedSecret)))
							.awaitUninterruptibly();
				} catch (NoPeerConnectionException e) {
					Assert.fail();
				}
			}

			@Override
			protected Void doExecute() throws InvalidProcessStateException {
				try {
					sendDirect(message, getPublicKey(nodeB));
				} catch (SendFailedException e) {
					Assert.fail();
				}
				return null;
			}
		};
		TestExecutionUtil.executeProcessTillSucceded(step);

		// wait till response message gets handled
		H2HWaiter waiter = new H2HWaiter(10);
		BaseNetworkContent content = null;
		do {
			waiter.tickASecond();
			content = nodeA.getDataManager().get(parametersA);
		} while (content == null);

		// load and verify if same secret was shared
		String receivedSecret = ((H2HTestData) content).getTestString();
		String originalSecret = ((H2HTestData) nodeB.getDataManager().get(parametersB)).getTestString();
		assertEquals(originalSecret, receivedSecret);
	}

	private PublicKey getPublicKey(NetworkManager networkManager) {
		try {
			return networkManager.getSession().getKeyPair().getPublic();
		} catch (NoSessionException e) {
			return null;
		}
	}

	@AfterClass
	public static void endTest() {
		NetworkTestUtil.shutdownNetwork(network);
		afterClass();
	}

}
