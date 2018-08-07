package org.tmt.encsubsystem.encassembly;


import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Adapter;
import akka.actor.typed.javadsl.AskPattern;
import akka.util.Timeout;
import csw.framework.javadsl.JComponentHandlers;
import csw.framework.scaladsl.CurrentStatePublisher;
import csw.messages.commands.CommandIssue;
import csw.messages.commands.CommandResponse;
import csw.messages.commands.ControlCommand;
import csw.messages.commands.Setup;
import csw.messages.framework.ComponentInfo;
import csw.messages.location.AkkaLocation;
import csw.messages.location.LocationRemoved;
import csw.messages.location.LocationUpdated;
import csw.messages.location.TrackingEvent;
import csw.messages.params.generics.JKeyTypes;
import csw.messages.scaladsl.TopLevelActorMessage;
import csw.services.command.javadsl.JCommandService;
import csw.services.command.scaladsl.CommandResponseManager;
import csw.services.command.scaladsl.CurrentStateSubscription;
import csw.services.config.api.javadsl.IConfigClientService;
import csw.services.config.client.javadsl.JConfigClientFactory;
import csw.services.event.javadsl.IEventService;
import csw.services.location.javadsl.ILocationService;
import csw.services.logging.javadsl.ILogger;
import csw.services.logging.javadsl.JLoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Domain specific logic should be written in below handlers.
 * This handlers gets invoked when component receives messages/commands from other component/entity.
 * For example, if one component sends Submit(Setup(args)) command to EncHcd,
 * This will be first validated in the supervisor and then forwarded to Component TLA which first invokes validateCommand hook
 * and if validation is successful, then onSubmit hook gets invoked.
 * You can find more information on this here : https://tmtsoftware.github.io/csw-prod/framework.html
 */
public class JEncAssemblyHandlers extends JComponentHandlers {


    // what should be the initial state when assembly is just deployed, even before the onInitialize hook get called.
    public enum LifecycleState {
        Initialized, Running, Offline, Online
    }

    public enum OperationalState {
        Idle, Ready, Following, Slewing, Tracking, InPosition, Halted, Faulted
    }

    private ILogger log;
    private CommandResponseManager commandResponseManager;
    private CurrentStatePublisher currentStatePublisher;
    private ActorContext<TopLevelActorMessage> actorContext;
    private ILocationService locationService;
    private ComponentInfo componentInfo;

    private IConfigClientService configClientApi;

    private ActorRef<JCommandHandlerActor.CommandMessage> commandHandlerActor;
    private ActorRef<JEventHandlerActor.EventMessage> eventHandlerActor;
    private ActorRef<JLifecycleActor.LifecycleMessage> lifecycleActor;
    private ActorRef<JMonitorActor.MonitorMessage> monitorActor;


    private Optional<JCommandService> hcdCommandService = Optional.empty();
    private Optional<CurrentStateSubscription> subscription = Optional.empty();

    JEncAssemblyHandlers(
            ActorContext<TopLevelActorMessage> ctx,
            ComponentInfo componentInfo,
            CommandResponseManager commandResponseManager,
            CurrentStatePublisher currentStatePublisher,
            ILocationService locationService,
            IEventService eventService,
            JLoggerFactory loggerFactory
    ) {
        super(ctx, componentInfo, commandResponseManager, currentStatePublisher, locationService, eventService, loggerFactory);
        this.currentStatePublisher = currentStatePublisher;
        this.log = loggerFactory.getLogger(getClass());
        this.commandResponseManager = commandResponseManager;
        this.actorContext = ctx;
        this.locationService = locationService;
        this.componentInfo = componentInfo;

        configClientApi = JConfigClientFactory.clientApi(Adapter.toUntyped(actorContext.getSystem()), locationService);

        // Load the configuration from the configuration service
        // Config assemblyConfig = getAssemblyConfig();
        log.debug(() -> "Spawning Handler Actors in assembly");
        commandHandlerActor = ctx.spawnAnonymous(JCommandHandlerActor.behavior(commandResponseManager, hcdCommandService, Boolean.TRUE, loggerFactory, Optional.empty()));

        eventHandlerActor = ctx.spawnAnonymous(JEventHandlerActor.behavior(loggerFactory));

        lifecycleActor = ctx.spawnAnonymous(JLifecycleActor.behavior(commandResponseManager, hcdCommandService, configClientApi, commandHandlerActor, loggerFactory));

        monitorActor = ctx.spawnAnonymous(JMonitorActor.behavior(JEncAssemblyHandlers.LifecycleState.Initialized, JEncAssemblyHandlers.OperationalState.Idle, loggerFactory, eventHandlerActor, commandHandlerActor));

    }

    @Override
    public CompletableFuture<Void> jInitialize() {

        CompletableFuture<Void> cf = new CompletableFuture<>();
        log.debug(() -> "initializing enc assembly");
        lifecycleActor.tell(new JLifecycleActor.InitializeMessage(cf));
        return cf;
    }

    @Override
    public CompletableFuture<Void> jOnShutdown() {
        return CompletableFuture.runAsync(() -> {
            log.debug(() -> "shutdown enc assembly");
            lifecycleActor.tell(new JLifecycleActor.ShutdownMessage());
        });
    }

    @Override
    public void onLocationTrackingEvent(TrackingEvent trackingEvent) {
        log.debug(() -> "assembly getting notified - location changed ");
        if (trackingEvent instanceof LocationUpdated) {
            AkkaLocation hcdAkkaLocation = (AkkaLocation) ((LocationUpdated) trackingEvent).location();
            JCommandService jCommandService= new JCommandService(hcdAkkaLocation, actorContext.getSystem());
            hcdCommandService = Optional.of(jCommandService);
            // set up Hcd CurrentState subscription to be handled by the monitor actor
            subscription = Optional.of(hcdCommandService.get().subscribeCurrentState(currentState -> {
                        monitorActor.tell(new JMonitorActor.CurrentStateMessage(currentState));
                    }
            ));

            log.debug(() -> "connection to hcd from assembly received");

        } else if (trackingEvent instanceof LocationRemoved) {
            // do something for the tracked location when it is no longer available

            hcdCommandService = Optional.empty();
            // FIXME: not sure if this is necessary
            subscription.ifPresent(subscription -> subscription.unsubscribe());
        }

        // send messages to command handler and monitor actors
        commandHandlerActor.tell(new JCommandHandlerActor.UpdateTemplateHcdMessage(hcdCommandService));
        lifecycleActor.tell(new JLifecycleActor.UpdateHcdCommandServiceMessage(hcdCommandService));
        monitorActor.tell(new JMonitorActor.LocationEventMessage(hcdCommandService));
    }

    @Override
    public CommandResponse validateCommand(ControlCommand controlCommand) {
        log.debug(() -> "validating command enc assembly " + controlCommand.commandName().name());
        CommandResponse.Accepted accepted = new CommandResponse.Accepted(controlCommand.runId());
        switch (controlCommand.commandName().name()) {
            case "move":
                log.debug(() -> "Validating command parameters and operational state");
                //Parameter based validation
                if (((Setup) controlCommand).get("mode", JKeyTypes.StringKey()).isEmpty()) {

                    return new CommandResponse.Invalid(controlCommand.runId(), new CommandIssue.MissingKeyIssue("Move command is missing mode parameter"));
                }
                //State based validation
                if (!isStateValid(askOperationalStateFromMonitor(monitorActor, actorContext.getSystem()))) {
                    return new CommandResponse.Invalid(controlCommand.runId(), new CommandIssue.WrongInternalStateIssue("Assembly is not in valid operational state"));
                }
                return accepted;

            case "follow":

                //State based validation
                if (!isStateValid(askOperationalStateFromMonitor(monitorActor, actorContext.getSystem()))) {
                    return new CommandResponse.Invalid(controlCommand.runId(), new CommandIssue.WrongInternalStateIssue("Assembly is not in valid operational state"));
                }
                //Immediate command implementation, on submit hook will not be called.
                return executeFollowCommandAndReturnResponse(controlCommand);
            case "startup":
                return accepted;
            case "shutdown":
                return accepted;

            default:
                log.debug(() -> "invalid command");
                CommandResponse.Invalid invalid = new CommandResponse.Invalid(controlCommand.runId(), new CommandIssue.UnsupportedCommandIssue("Command is not supported"));
                return invalid;

        }

    }

    @Override
    public void onSubmit(ControlCommand controlCommand) {
        log.info(() -> "Assembly received command - " + controlCommand);
        switch (controlCommand.commandName().name()) {
            case "startup":
                lifecycleActor.tell(new JLifecycleActor.SubmitCommandMessage(controlCommand));
                break;
            case "shutdown":
                lifecycleActor.tell(new JLifecycleActor.SubmitCommandMessage(controlCommand));
                break;
            case "move":
                commandHandlerActor.tell(new JCommandHandlerActor.SubmitCommandMessage(controlCommand));
                break;
            case "follow":
                commandHandlerActor.tell(new JCommandHandlerActor.SubmitCommandMessage(controlCommand));
                break;


        }

    }

    @Override
    public void onOneway(ControlCommand controlCommand) {
        log.debug(() -> "processing oneway command to enc assembly");
    }

    @Override
    public void onGoOffline() {
        log.debug(() -> "in onGoOffline()");
        commandHandlerActor.tell(new JCommandHandlerActor.GoOfflineMessage());
    }

    @Override
    public void onGoOnline() {
        log.debug(() -> "in onGoOnline()");

        commandHandlerActor.tell(new JCommandHandlerActor.GoOnlineMessage());
    }

    /**
     * This method send command to command handler and return response of execution.
     * The command execution is blocking, response is not return until command processing is completed
     *
     * @param controlCommand
     * @return
     */
    private CommandResponse executeFollowCommandAndReturnResponse(ControlCommand controlCommand) {
        //submitting command to commandHandler actor and waiting for completion.
        try {
            CompletionStage<JCommandHandlerActor.ImmediateResponseMessage> reply = AskPattern.ask(commandHandlerActor, (ActorRef<JCommandHandlerActor.ImmediateResponseMessage> replyTo) ->
                    new JCommandHandlerActor.ImmediateCommandMessage(controlCommand, replyTo), new Timeout(10, TimeUnit.SECONDS), actorContext.getSystem().scheduler());

            return reply.toCompletableFuture().get().commandResponse;
        } catch (Exception e) {
            return new CommandResponse.Error(controlCommand.runId(), "Error occurred while executing follow command");
        }
    }

    /**
     * Performing state based validation
     *
     * @param operationalState
     * @return
     */
    private boolean isStateValid(OperationalState operationalState) {
        return operationalState == OperationalState.Ready ||
                operationalState == OperationalState.Slewing ||
                operationalState == OperationalState.Tracking ||
                operationalState == OperationalState.InPosition;
    }

    /**
     * Getting state from monitor actor to perform state related validation etc.
     * This is a blocking call to actor
     *
     * @return
     */
    public static OperationalState askOperationalStateFromMonitor(ActorRef<JMonitorActor.MonitorMessage> actor, ActorSystem system) {

        final JMonitorActor.AssemblyStatesResponseMessage assemblyStates;
        try {
            assemblyStates = AskPattern.ask(actor, (ActorRef<JMonitorActor.AssemblyStatesResponseMessage> replyTo) ->
                            new JMonitorActor.AssemblyStatesAskMessage(replyTo)
                    , new Timeout(10, TimeUnit.SECONDS), system.scheduler()).toCompletableFuture().get();
            //  log.debug(() -> "Got Assembly state from monitor actor - " + assemblyStates.assemblyOperationalState + " ,  " + assemblyStates.assemblyLifecycleState);
            return assemblyStates.assemblyOperationalState;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

    }

}
