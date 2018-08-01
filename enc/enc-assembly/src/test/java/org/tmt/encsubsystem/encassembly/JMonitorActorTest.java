package org.tmt.encsubsystem.encassembly;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import csw.services.command.javadsl.JCommandService;
import csw.services.command.scaladsl.CommandResponseManager;
import csw.services.logging.javadsl.JLoggerFactory;
import org.junit.*;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class JMonitorActorTest {
    @ClassRule
    public static final TestKitJunitResource testKit = new TestKitJunitResource();

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    CommandResponseManager commandResponseManager;

    TestProbe<JEventHandlerActor.EventMessage> eventHandlerActor;
    TestProbe<JCommandHandlerActor.CommandMessage> commandHandlerActor;


    @Mock
    JCommandService hcdCommandService;

    ActorRef<JMonitorActor.MonitorMessage> monitorActor;
    JLoggerFactory jLoggerFactory;

    @Before
    public void setUp() throws Exception {
        jLoggerFactory = new JLoggerFactory("enc-test-logger");
        eventHandlerActor = testKit.createTestProbe();
        commandHandlerActor = testKit.createTestProbe();
        monitorActor = testKit.spawn(JMonitorActor.behavior(JEncAssemblyHandlers.LifecycleState.Initialized, JEncAssemblyHandlers.OperationalState.Idle, jLoggerFactory, eventHandlerActor.getRef(), commandHandlerActor.getRef()));
    }

    @After
    public void tearDown() throws Exception {
    }

    /**
     * Given Assembly is initialized or running or ready
     * when hcd connection is lost
     * then monitor actor should transition assembly to faulted state
     */
    @Test
    public void connectionFailureTest() throws InterruptedException {
        monitorActor.tell(new JMonitorActor.LocationEventMessage(Optional.empty()));
        Thread.sleep(TestConstants.ACTOR_MESSAGE_PROCESSING_DELAY);
        JEncAssemblyHandlers.OperationalState operationalState = JEncAssemblyHandlers.askOperationalStateFromMonitor(monitorActor, testKit.system());
        assertEquals(operationalState, JEncAssemblyHandlers.OperationalState.Faulted);
    }


}