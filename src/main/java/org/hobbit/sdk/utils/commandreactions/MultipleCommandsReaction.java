package org.hobbit.sdk.utils.commandreactions;

import com.google.gson.Gson;
import com.rabbitmq.client.MessageProperties;
import org.hobbit.core.Commands;
import org.hobbit.core.components.Component;
import org.hobbit.core.data.StartCommandData;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.hobbit.sdk.docker.AbstractDockerizer;
import org.hobbit.sdk.utils.ComponentsExecutor;
import org.hobbit.sdk.utils.CommandQueueListener;
import org.hobbit.sdk.utils.CommandSender;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;


public class MultipleCommandsReaction implements CommandReaction {
    private static final Logger logger = LoggerFactory.getLogger(MultipleCommandsReaction.class);

    private ComponentsExecutor componentsExecutor;
    private CommandQueueListener commandQueueListener;

    private Component benchmarkController;
    private Component dataGenerator;
    private Component taskGenerator;
    private Component evalStorage;
    private Component evalModule;
    private Component systemAdapter;

    private String benchmarkControllerImageName;
    private String dataGeneratorImageName;
    private String taskGeneratorImageName;
    private String evalStorageImageName;
    private String evalModuleImageName;
    private String systemAdapterImageName;

    private int dataGeneratorsCount = 0;
    private int taskGeneratorsCount = 0;
    private int systemContainersCount = 0;

    private Gson gson = new Gson();

    private boolean benchmarkReady = false;
    private boolean dataGenReady = false;
    private boolean taskGenReady = false;
    private boolean evalStorageReady = false;
    private boolean systemReady = false;

    private boolean startBenchmarkCommandSent = false;

    public MultipleCommandsReaction(Builder builder){
        this.componentsExecutor = builder.componentsExecutor;
        this.commandQueueListener = builder.commandQueueListener;

        this.benchmarkController=builder.benchmarkController;
        this.dataGenerator=builder.dataGenerator;
        this.taskGenerator=builder.taskGenerator;
        this.evalStorage=builder.evalStorage;
        this.evalModule=builder.evalModule;
        this.systemAdapter=builder.systemAdapter;

        this.benchmarkControllerImageName = builder.benchmarkControllerImageName;
        this.dataGeneratorImageName = builder.dataGeneratorImageName;
        this.taskGeneratorImageName = builder.taskGeneratorImageName;
        this.evalStorageImageName = builder.evalStorageImageName ;
        this.evalModuleImageName = builder.evalModuleImageName;
        this.systemAdapterImageName = builder.systemAdapterImageName;
    }

    @Override
    public void handleCmd(Byte command, byte[] bytes, String replyTo) throws Exception {

        if (command == Commands.DOCKER_CONTAINER_START){
            String dataString = RabbitMQUtils.readString(bytes);
            StartCommandData startCommandData = gson.fromJson(dataString, StartCommandData.class);

            logger.debug("CONTAINER_START signal received with imageName="+startCommandData.image+"");

            Component compToSubmit = null;
            String containerId = null;

            if (benchmarkController!=null && startCommandData.image.equals(benchmarkControllerImageName)) {
                compToSubmit = benchmarkController;
                containerId = benchmarkControllerImageName;
            }

            if (dataGenerator!=null && startCommandData.image.equals(dataGeneratorImageName)) {
                if(AbstractDockerizer.class.isInstance(dataGenerator)){
                    compToSubmit = ((AbstractDockerizer)dataGenerator).clone();
                    containerId = ((AbstractDockerizer)compToSubmit).getContainerName();
                }else {
                    compToSubmit = dataGenerator.getClass().getConstructor().newInstance();
                    containerId = dataGeneratorImageName+"_"+dataGeneratorsCount;
                }
                dataGeneratorsCount++;
            }

            if(taskGenerator!=null && startCommandData.image.equals(taskGeneratorImageName)) {
                if(AbstractDockerizer.class.isInstance(taskGenerator)){
                    compToSubmit = ((AbstractDockerizer)taskGenerator).clone();
                    containerId = ((AbstractDockerizer)compToSubmit).getContainerName();
                }else {
                    compToSubmit = taskGenerator.getClass().getConstructor().newInstance();
                    containerId = taskGeneratorImageName+"_"+taskGeneratorsCount;
                }
                taskGeneratorsCount++;
            }

            if(evalStorage!=null && startCommandData.image.equals(evalStorageImageName)){
                compToSubmit = evalStorage;
                containerId = evalStorageImageName;
            }

            if(evalModule!=null && startCommandData.image.equals(evalModuleImageName)) {
                compToSubmit = evalModule;
                containerId = evalModuleImageName;
            }

            if(systemAdapter !=null && startCommandData.image.equals(systemAdapterImageName)) {
                compToSubmit = systemAdapter;
                containerId = systemAdapterImageName;
                //systemContainersCount++;
                //containerId = systemAdapterImageName+"_"+String.valueOf(systemContainersCount);
            }

            if(compToSubmit!=null){

                componentsExecutor.submit(compToSubmit, containerId, startCommandData.getEnvironmentVariables());
                synchronized (this) {
                    if (containerId!=null){
                        try {
                            new CommandSender(containerId.getBytes(), MessageProperties.PERSISTENT_BASIC, replyTo).send();

                        } catch (Exception e) {
                            Assert.fail(e.getMessage());
                        }
                    }else{
                        String test="123";
                    }
                }
            }else{
                throw new Exception("No component to start as imageName="+startCommandData.image);

            }
        }


        if(command==Commands.DOCKER_CONTAINER_TERMINATED){
            CommandSender commandSender = null;
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            String containerName = RabbitMQUtils.readString(buffer);

            logger.debug("DOCKER_CONTAINER_TERMINATED {} received", containerName);

            String commandToSend = null;
            //if(containerName.equals(dataGenContainerId))
            if(containerName.equals(dataGeneratorImageName)){
                dataGeneratorsCount--;
                if(dataGeneratorsCount==0) {
                    commandSender = new CommandSender(Commands.DATA_GENERATION_FINISHED);
                    commandToSend = "DATA_GENERATION_FINISHED";
                }
            }

            //if(containerName.equals(taskGenContainerId))
            if(containerName.equals(taskGeneratorImageName)) {
                taskGeneratorsCount--;
                if(taskGeneratorsCount==0){
                    commandSender = new CommandSender(Commands.TASK_GENERATION_FINISHED);
                    commandToSend = "TASK_GENERATION_FINISHED";
                }
            }

            if(containerName.equals(systemAdapterImageName)){
                String abc = "123";
//                systemContainersCount--;
//                if(systemContainersCount==0){
//                    commandSender = new CommandSender(SYSTEM_CONTAINERS_FINISHED);
//                    commandToSend = "SYSTEM_CONTAINERS_FINISHED";
//                }
            }
 

            synchronized (this){
                if (commandSender!=null){
                    try {
                        logger.debug("Sending "+commandToSend+" signal");
                        commandSender.send();
                    } catch (Exception e) {
                        //Assert.fail(e.getMessage());
                        logger.error(e.getMessage());
                    }
                }
            }
        }

        if (command == Commands.BENCHMARK_FINISHED_SIGNAL){
            logger.debug("BENCHMARK_FINISHED_SIGNAL received");
            try {
                commandQueueListener.terminate();
                componentsExecutor.shutdown();
            } catch (InterruptedException e) {
                System.out.println(e.getMessage());
                //Assert.fail(e.getMessage());
            }
        }

        if (command == Commands.BENCHMARK_READY_SIGNAL) {
            benchmarkReady = true;
            logger.debug("BENCHMARK_READY_SIGNAL signal received");
        }

        if (command == Commands.DATA_GENERATOR_READY_SIGNAL) {
            dataGenReady = true;
            logger.debug("DATA_GENERATOR_READY_SIGNAL signal received");
        }

        if (command == Commands.TASK_GENERATOR_READY_SIGNAL) {
            taskGenReady = true;
            logger.debug("TASK_GENERATOR_READY_SIGNAL signal received");
        }

        if (command == Commands.EVAL_STORAGE_READY_SIGNAL) {
            evalStorageReady = true;
            logger.debug("EVAL_STORAGE_READY_SIGNAL signal received");
        }

        if (command == Commands.SYSTEM_READY_SIGNAL) {
            systemReady = true;
            logger.debug("SYSTEM_READY_SIGNAL signal received");
        }

        synchronized (this){
            if (benchmarkReady &&
                    (dataGenerator==null || dataGenReady) &&
                    (taskGenerator==null || taskGenReady) &&
                    (evalStorage==null || evalStorageReady) &&
                    systemReady && !startBenchmarkCommandSent) {
                startBenchmarkCommandSent = true;
                try {
                    logger.debug("sending START_BENCHMARK_SIGNAL");
                    new CommandSender(Commands.START_BENCHMARK_SIGNAL, systemAdapterImageName).send();
                } catch (Exception e) {
                    logger.error(e.getMessage());
                    //Assert.fail(e.getMessage());
                }
            }
        }

    }

    public static class Builder{

        private ComponentsExecutor componentsExecutor;
        private CommandQueueListener commandQueueListener;

        private Component benchmarkController;
        private Component dataGenerator;
        private Component taskGenerator;
        private Component evalStorage;
        private Component evalModule;
        private Component systemAdapter;

        private String benchmarkControllerImageName;
        private String dataGeneratorImageName;
        private String taskGeneratorImageName;
        private String evalStorageImageName;
        private String evalModuleImageName;
        private String systemAdapterImageName;

//    private String dataGenContainerId;
//    private String taskGenContainerId;
        //private String systemAdapterImageName;
//    private String evalModuleContainerId;
//    private String evalStorageContainerId;

        public Builder(ComponentsExecutor componentsExecutor, CommandQueueListener commandQueueListener){
            this.componentsExecutor = componentsExecutor;
            this.commandQueueListener = commandQueueListener;
        }

        public Builder benchmarkController(Component component){
            this.benchmarkController = component;
            return this;
        }

        public Builder benchmarkControllerImageName(String value){
            this.benchmarkControllerImageName = value;
            return this;
        }

        public Builder dataGenerator(Component component){
            this.dataGenerator = component;
            return this;
        }

        public Builder dataGeneratorImageName(String value){
            this.dataGeneratorImageName = value;
            return this;
        }

        public Builder taskGenerator(Component component){
            this.taskGenerator = component;
            return this;
        }

        public Builder taskGeneratorImageName(String value){
            this.taskGeneratorImageName = value;
            return this;
        }

        public Builder evalStorage(Component component){
            this.evalStorage = component;
            return this;
        }

        public Builder evalStorageImageName(String value){
            this.evalStorageImageName = value;
            return this;
        }

        public Builder systemAdapter(Component value){
            this.systemAdapter = value;
            return this;
        }

        public Builder systemAdapterImageName(String value){
            this.systemAdapterImageName = value;
            return this;
        }


        public Builder evalModule(Component value){
            this.evalModule = value;
            return this;
        }

        public Builder evalModuleImageName(String value){
            this.evalModuleImageName = value;
            return this;
        }

        public MultipleCommandsReaction build(){
            if(systemAdapterImageName ==null){
                logger.warn("SystemAdapter not specified. Nothing will be submitted");
            }
            return new MultipleCommandsReaction(this);
        }
    }
}
