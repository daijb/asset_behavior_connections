package org.daijb.huat.services;

import com.alibaba.fastjson.JSON;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.daijb.huat.services.entity.FlowEntity;
import org.daijb.huat.services.utils.JavaKafkaConfigurer;
import org.daijb.huat.services.utils.StringUtil;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * @author daijb
 * @date 2021/2/8 14:13
 */
public class KafkaMessageStreaming {

    public static void main(String[] args) throws Exception {
        KafkaMessageStreaming kafkaMessageStreaming = new KafkaMessageStreaming();
        kafkaMessageStreaming.run(args);
    }

    public void run(String[] args) throws Exception {
        StreamExecutionEnvironment streamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment();
        streamExecutionEnvironment.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

        EnvironmentSettings fsSettings = EnvironmentSettings.newInstance().useOldPlanner().inStreamingMode().build();
        //创建 TableEnvironment
        StreamTableEnvironment streamTableEnvironment = StreamTableEnvironment.create(streamExecutionEnvironment, fsSettings);

        //每隔10s进行启动一个检查点【设置checkpoint的周期】
        streamExecutionEnvironment.enableCheckpointing(10000);
        //设置模式为：exactly_one，仅一次语义
        streamExecutionEnvironment.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
        //确保检查点之间有1s的时间间隔【checkpoint最小间隔】
        streamExecutionEnvironment.getCheckpointConfig().setMinPauseBetweenCheckpoints(1000);
        //检查点必须在10s之内完成，或者被丢弃【checkpoint超时时间】
        streamExecutionEnvironment.getCheckpointConfig().setCheckpointTimeout(10000);
        //同一时间只允许进行一次检查点
        streamExecutionEnvironment.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
        //表示一旦Flink程序被cancel后，会保留checkpoint数据，以便根据实际需要恢复到指定的checkpoint
        //streamExecutionEnvironment.getCheckpointConfig().enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        //设置statebackend,将检查点保存在hdfs上面，默认保存在内存中。这里先保存到本地
        //streamExecutionEnvironment.setStateBackend(new FsStateBackend("file:///Users/temp/cp/"));

        //加载kafka配置信息
        Properties kafkaProperties = JavaKafkaConfigurer.getKafkaProperties(args);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getProperty("bootstrap.servers"));
        //可g根据实际拉取数据等设置此值，默认30s
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        //每次poll的最大数量
        //注意该值不要改得太大，如果poll太多数据，而不能在下次poll之前消费完，则会触发一次负载均衡，产生卡顿
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 30);
        //当前消费实例所属的消费组
        //属于同一个组的消费实例，会负载消费消息
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getProperty("group.id"));

        FlinkKafkaConsumer010 kafkaConsumer = new FlinkKafkaConsumer010<>(kafkaProperties.getProperty("topic"), new SimpleStringSchema(), props);

        SingleOutputStreamOperator processStream = streamExecutionEnvironment.addSource(kafkaConsumer)
                .process(new ParserKafkaProcessFunction());

        /**
         * 数据过滤
         */
        DataStream<FlowEntity> filterSource = processStream.filter(new FilterFunction<FlowEntity>() {

            @Override
            public boolean filter(FlowEntity flowEntity) throws Exception {
                // 匹配含有资产的
                if (StringUtil.isEmpty(flowEntity.getDstId()) || StringUtil.isEmpty(flowEntity.getSrcId())) {
                    return false;
                }
                return true;
            }
        });

        /**
         * 转换数据格式
         */
        /*DataStream<JSONObject> kafkaJson = filterSource.map(new MapFunction<FlowEntity, JSONObject>() {
            @Override
            public JSONObject map(FlowEntity flowEntity) throws Exception {
                String jsonStr = JSONObject.toJSONString(flowEntity, SerializerFeature.PrettyFormat);
                return JSONObject.parseObject(jsonStr);
            }
        });*/

        // 创建临时试图表
        streamTableEnvironment.createTemporaryView("kafka_source", filterSource, "srcId,srcIp,dstId,dstIp,areaId,flowId,rTime,rowtime.rowtime");

        // 注册UDF
        streamTableEnvironment.registerFunction("UdfTimestampConverter", new UdfTimestampConverter());

        // 运行sql
        String queryExpr = "select srcId as srcId,srcIp as srcIp,dstId as dstId,dstIp as dstIp,areaId as areaId,flowId as flowId,rTime " +
                " from kafka_source ";
        //+ " group by areaId,srcId,srcIp,dstId,dstIp,flowId,rTime,TUMBLE(rowtime, INTERVAL '10' SECOND)";

        // 获取结果
        Table table = streamTableEnvironment.sqlQuery(queryExpr);

        DataStream<FlowEntity> flowEntityDataStream = streamTableEnvironment.toAppendStream(table, FlowEntity.class);

        flowEntityDataStream.print().setParallelism(1);

        // 全局唯一
        final AssetConnectionExecutive assetConnectionExecutive = new AssetConnectionExecutive();

        /**
         * 查找具有连接关系的数据
         */
        flowEntityDataStream.filter(new FilterFunction<FlowEntity>() {
            @Override
            public boolean filter(FlowEntity flowEntity) throws Exception {
                return assetConnectionExecutive.assetBehaviorFilter(flowEntity);
            }
        }).addSink(new MySqlTwoPhaseCommitSink()).name("MySqlTwoPhaseCommitSink");

        streamExecutionEnvironment.execute("kafka message streaming start ....");
    }

    /**
     * 解析kafka数据
     */
    private static class ParserKafkaProcessFunction extends ProcessFunction<String, FlowEntity> {

        @Override
        public void processElement(String value, Context ctx, Collector<FlowEntity> out) throws Exception {
            //输出到主流
            out.collect(JSON.parseObject(value, FlowEntity.class));
            // 输出到侧输出流
            //ctx.output(new OutputTag<>());
        }
    }

    /**
     * 自定义UDF
     */
    public static class UdfTimestampConverter extends ScalarFunction {

        /**
         * 默认转换为北京时间
         *
         * @param timestamp flink Timestamp 格式时间
         * @param format    目标格式,如"YYYY-MM-dd HH:mm:ss"
         * @return 目标时区的时间
         */
        public String eval(Timestamp timestamp, String format) {

            LocalDateTime noZoneDateTime = timestamp.toLocalDateTime();
            ZonedDateTime utcZoneDateTime = ZonedDateTime.of(noZoneDateTime, ZoneId.of("UTC"));

            ZonedDateTime targetZoneDateTime = utcZoneDateTime.withZoneSameInstant(ZoneId.of("+08:00"));

            return targetZoneDateTime.format(DateTimeFormatter.ofPattern(format));
        }

        /**
         * 转换为指定时区时间
         *
         * @param timestamp  flink Timestamp 格式时间
         * @param format     目标格式,如"YYYY-MM-dd HH:mm:ss"
         * @param zoneOffset 目标时区偏移量
         * @return 目标时区的时间
         */
        public String eval(Timestamp timestamp, String format, String zoneOffset) {

            LocalDateTime noZoneDateTime = timestamp.toLocalDateTime();
            ZonedDateTime utcZoneDateTime = ZonedDateTime.of(noZoneDateTime, ZoneId.of("UTC"));

            ZonedDateTime targetZoneDateTime = utcZoneDateTime.withZoneSameInstant(ZoneId.of(zoneOffset));

            return targetZoneDateTime.format(DateTimeFormatter.ofPattern(format));
        }
    }

}
