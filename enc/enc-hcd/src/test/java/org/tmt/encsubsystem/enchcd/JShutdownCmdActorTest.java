package org.tmt.encsubsystem.enchcd;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import csw.messages.commands.CommandName;
import csw.messages.commands.CommandResponse;
import csw.messages.commands.ControlCommand;
import csw.messages.commands.Setup;
import csw.messages.params.models.Prefix;
import csw.services.command.CommandResponseManager;
import csw.services.logging.javadsl.JLoggerFactory;
import org.junit.*;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Duration;
import java.util.Optional;

import static org.mockito.Mockito.verify;

/**
 * This is an Actor Level Test.
 * <p>
 * Test case can be -
 * Handler level
 * Actor level
 * <p>
 * Actor Level -
 * Sending different message and checking if
 * it respond with a message
 * it create any child actor
 * it send message to any other actor
 * it changes its state
 * it crashes
 * <p>
 * Handler Level -
 * validation tests
 * failed validation and successful validation
 * <p>
 * command tests
 * test for immediate command
 * test for long running command
 */
public class JShutdownCmdActorTest {

    @ClassRule
    public static final TestKitJunitResource testKit = new TestKitJunitResource();

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    CommandResponseManager commandResponseManager;

    JLoggerFactory jLoggerFactory;
    TestProbe<JStatePublisherActor.StatePublisherMessage> statePublisherMessageTestProbe;
    ActorRef<ControlCommand> shutdownCmdActor;

    @Before
    public void setUp() throws Exception {
        jLoggerFactory = new JLoggerFactory("enc-test-logger");
        statePublisherMessageTestProbe = testKit.createTestProbe();
        shutdownCmdActor = testKit.spawn(JShutdownCmdActor.behavior(commandResponseManager, statePublisherMessageTestProbe.getRef(), jLoggerFactory));
    }

    @After
    public void tearDown() throws Exception {
    }

    /**
     * given the HCD is running, subsystem is running
     * when valid shutdown command is send
     * then command should successfully complete and state should transition to initialized,
     * also state publisher actor should  get state change message.
     */
    @Test
    public void shutdownCommandCompletion() {

        Setup shutdownCmd = new Setup(new Prefix("enc.enc-test"), new CommandName("shutdown"), Optional.empty());
        shutdownCmdActor.tell(shutdownCmd);
        //checking if statePublisher Actor received state change message
        statePublisherMessageTestProbe.expectMessage(Duration.ofSeconds(10), new JStatePublisherActor.UnInitializedMessage());

        verify(commandResponseManager).addOrUpdateCommand(shutdownCmd.runId(), new CommandResponse.Completed(shutdownCmd.runId()));
    }
}