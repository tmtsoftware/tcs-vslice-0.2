package org.tmt.encsubsystem.enchcd;

import akka.actor.testkit.typed.javadsl.TestKitJunitResource;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;

import csw.services.command.scaladsl.CommandResponseManager;
import csw.services.config.api.javadsl.IConfigClientService;
import csw.services.config.api.models.ConfigData;
import csw.services.logging.javadsl.JLoggerFactory;
import org.junit.*;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class JLifecycleActorTest {

    @ClassRule
    public static final TestKitJunitResource testKit = new TestKitJunitResource();

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    CommandResponseManager commandResponseManager;
    @Mock
    private IConfigClientService configClientApi;
    //copy of original configuration for testing
    String fileLocation = "tcs_test.conf";
    ConfigData configData;

    JLoggerFactory jLoggerFactory;
    TestProbe<JStatePublisherActor.StatePublisherMessage> statePublisherMessageTestProbe;
    ActorRef<JLifecycleActor.LifecycleMessage> lifecycleCmdActor;

    @Before
    public void setUp() throws Exception {
        //getting config data from  test resource file to mock actual config service call.
        URL url = JLifecycleActorTest.class.getClassLoader().getResource(fileLocation);
        Path filePath = Paths.get(url.toURI());
        configData = ConfigData.fromPath(filePath);

        jLoggerFactory = new JLoggerFactory("enc-test-logger");
        statePublisherMessageTestProbe = testKit.createTestProbe();
        lifecycleCmdActor = testKit.spawn(JLifecycleActor.behavior(commandResponseManager, statePublisherMessageTestProbe.getRef(), configClientApi, jLoggerFactory));

    }

    @After
    public void tearDown() throws Exception {
    }

    /**
     * Given lifecycle actor is created,
     * when Initialize message is send to lifecycle actor,
     * then it should load configuration using configuration service,
     *      tell state publisher actor to start publishing hcd current states
     *      and mark complete the completableFuture.
     */
    @Test
    public void testOnInitializeMessage() {
        when(configClientApi.getActive(Paths.get("/org/tmt/tcs/tcs_test.conf"))).thenReturn(CompletableFuture.completedFuture(Optional.of(configData)));
        CompletableFuture<Void> cf = new CompletableFuture<>();
        lifecycleCmdActor.tell(new JLifecycleActor.InitializeMessage(cf));
        statePublisherMessageTestProbe.expectMessage(Duration.ofSeconds(10), new JStatePublisherActor.StartMessage());
        try {
            cf.get(5, TimeUnit.SECONDS);
            assertTrue(cf.isDone());
        } catch (Exception e) {
            assertTrue(false);
        }
    }
}