package org.tmt.encsubsystem.encassembly;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.MutableBehavior;
import akka.actor.typed.javadsl.ReceiveBuilder;
import akka.util.Timeout;
import csw.messages.commands.CommandResponse;
import csw.messages.commands.ControlCommand;
import csw.messages.params.models.Prefix;
import csw.services.command.CommandResponseManager;
import csw.services.command.javadsl.JCommandService;

import csw.services.logging.javadsl.ILogger;
import csw.services.logging.javadsl.JLoggerFactory;
import scala.concurrent.duration.FiniteDuration;

import java.util.Optional;
import java.util.concurrent.TimeUnit;


public class JShutdownCmdActor extends MutableBehavior<ControlCommand> {


    private ActorRef<JMonitorActor.MonitorMessage> monitorActor;
    private Prefix templateHcdPrefix = new Prefix("tcs.encA");

    private ActorContext<ControlCommand> actorContext;
    private JLoggerFactory loggerFactory;
    private ILogger log;
    private CommandResponseManager commandResponseManager;
    private Optional<JCommandService> hcdCommandService;


    private JShutdownCmdActor(ActorContext<ControlCommand> actorContext, CommandResponseManager commandResponseManager, Optional<JCommandService> hcdCommandService, JLoggerFactory loggerFactory, ActorRef<JMonitorActor.MonitorMessage> monitorActor) {
        this.actorContext = actorContext;
        this.loggerFactory = loggerFactory;
        this.log = loggerFactory.getLogger(actorContext, getClass());
        this.commandResponseManager = commandResponseManager;
        this.hcdCommandService = hcdCommandService;
        this.monitorActor = monitorActor;

    }

    public static <ControlCommand> Behavior<ControlCommand> behavior(CommandResponseManager commandResponseManager, Optional<JCommandService> hcdCommandService, JLoggerFactory loggerFactory, ActorRef<JMonitorActor.MonitorMessage> monitorActor) {
        return Behaviors.setup(ctx -> {
            return (MutableBehavior<ControlCommand>) new JShutdownCmdActor((ActorContext<csw.messages.commands.ControlCommand>) ctx, commandResponseManager, hcdCommandService,
                    loggerFactory, monitorActor);
        });
    }

    /**
     * This method receives messages sent to actor.
     * based on message type it forward message to its dedicated handler method.
     * @return
     */
    @Override
    public Behaviors.Receive<ControlCommand> createReceive() {

        ReceiveBuilder<ControlCommand> builder = receiveBuilder()
                .onMessage(ControlCommand.class,
                        command -> {
                            log.debug(() -> "Shutdown Received");
                            handleSubmitCommand(command);
                            return Behaviors.stopped();// actor stops itself, it is meant to only process one command.
                        });
        return builder.build();
    }

    private void handleSubmitCommand(ControlCommand command) {

        if (hcdCommandService.isPresent()) {
            log.debug(() -> "Submitting shutdown command from assembly to hcd");
            hcdCommandService.get()
                    .submitAndSubscribe(
                            command,
                            Timeout.durationToTimeout(FiniteDuration.apply(5, TimeUnit.SECONDS))
                    ).thenAccept(response -> {
                log.debug(() -> "received response from hcd");
                commandResponseManager.addOrUpdateCommand(command.runId(), response);
                monitorActor.tell(new JMonitorActor.UnInitializedMessage());
            });

        } else {
            //
            commandResponseManager.addOrUpdateCommand(command.runId(), new CommandResponse.Error(command.runId(), "Can't locate HCD"));

        }
    }


}
