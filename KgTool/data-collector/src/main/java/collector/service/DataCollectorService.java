package collector.service;

import collector.domain.apicontainer.ApiContainer;
import collector.domain.apicontainer.ContainerList;
import collector.domain.apinode.ApiNode;
import collector.domain.apinode.NodeList;
import collector.domain.apipod.ApiPod;
import collector.domain.apipod.PodList;
import collector.domain.apiservice.ApiAppService;
import collector.domain.apiservice.AppServiceList;
import collector.domain.entities.*;
import collector.domain.prom.ExpressionQueriesLabelsResponse;
import collector.domain.prom.ExpressionQueriesVectorResponse;
import collector.domain.prom.ResultVector;
import collector.domain.relationships.*;
import collector.domain.skywalkingTrace.*;
import collector.domain.trace.BinaryAnnotation;
//import collector.domain.trace.Span;
import collector.domain.trace.ZipkinSpan;
import collector.util.MatcherUrlRouterUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import javafx.scene.control.Alert;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DataCollectorService {

    //集群master机器的地址
    @Value("${k8s.master.ip}")
    private String masterIP;

    //neo4j的api服务器的地址
    @Value("${database.neo4j.ip}")
    private String neo4jDaoIP;

    @Value("${graph.app.ip}")
    private String graphAppIp;

    //promethsus的查询地址
    @Value("${k8s.promethsus.ip}")
    private String promethsusQuery;

    //zipkin的查询地址
    @Value("${k8s.zipkin.ip}")
    private String zipkinQuery;

    //skywalking的查询地址
    @Value("${k8s.skywalking.ip}")
    private String skywalkingQuery;

    //集群全部机器的ip地址
    @Value("${k8s.cluster.ips}")
    private String[] clusterIPs;

    //需要查询的容器的metric指标名称
    @Value("${promethesus.metrics}")
    private String[] containerMetricsNameVector;

    @Value("${promethesus.pod.metrics}")
    private String[] podMetricNames;

    private ArrayList<String> metricsName;

    //时间戳 在图谱更新的时候会附加在节点上
    //在图谱重整的时候此值将会被刷新
    //在更新Metric的时候这个值不会刷新
    private static String currTimestampString = "Not Set Yet";

    //当前的实体列表
    private static ConcurrentHashMap<String, VirtualMachine> vms = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, AppService> svcs = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, Pod> pods = new ConcurrentHashMap<>();
    // Skywalking Trace中没有对应的pod名，只能用实例IP对应
    private static ConcurrentHashMap<String, Pod> IPpods = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, Container> containers = new ConcurrentHashMap<>();
    //放的是name到实体的映射
    private static ConcurrentHashMap<String, ServiceAPI> apis = new ConcurrentHashMap<>();
    //记录指标名对应的指标实体
    private static ConcurrentHashMap<String, Metric> metrics = new ConcurrentHashMap<>();
    //记录已经统计过 服务间调用数量 的Trace
    private static HashSet<String> tracesRecord = new HashSet<>();
    //记录一个个上传到数据库的Trace
    private static HashSet<String> uploadedTraces = new HashSet<>();
    private static HashMap<String, VirtualMachine> IPToVM = new HashMap<>();
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private Gson gson;

    private SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");

    private final Object objLockForPeriodly = new Object();

    //平均耗时23秒
    @Scheduled(initialDelay=5000, fixedDelay =1000000000)
    public void updateFrameworkPeriodly() {
        synchronized (objLockForPeriodly){
            //记录当前时间
            currTimestampString = "" + new Date().getTime() / 1000;
            System.out.println("CurrTimestampString" + currTimestampString);

            SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
            System.out.println("[开始]定期刷新应用骨架 现在时间：" + dateFormat.format(new Date()));
            createRawFrameworkToKnowledgeGraph();
            System.out.println("[完成]定期刷新应用骨架 现在时间：" + dateFormat.format(new Date()));

        }
    }

    //6秒？
    @Scheduled(initialDelay = 70000, fixedDelay = 10000)
    public void uploadTracesPeriodly(){
        synchronized (objLockForPeriodly) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
            Date now = new Date();
            System.out.println("[开始]定期刷新调用关系 现在时间：" + dateFormat.format(now));
            uploadApiSvcRelationsSkywalking(now);
            System.out.println("[完成]定期刷新调用关系 现在时间：" + dateFormat.format(new Date()));
            now = new Date();
            System.out.println("[开始]定期上传Trace 现在时间：" + dateFormat.format(now));
            uploadEveryTraceSkywalking(now);
            System.out.println("[完成]定期上传Trace 现在时间：" + dateFormat.format(new Date()));
        }
    }

//    //平均耗时14秒
//    @Scheduled(initialDelay=100000, fixedDelay =150000)
//    public void updateMetricsPeriodly() {
//        synchronized (objLockForPeriodly) {
//            System.out.println("[开始]定期刷新应用指标数据 现在时间：" + dateFormat.format(new Date()));
//            updateMetrics();
//            uploadPodMetrics();
//            System.out.println("[完成]定期刷新应用指标数据 现在时间：" + dateFormat.format(new Date()));
//        }
//    }

    public String getCurrentTimestamp(){
        return currTimestampString;
    }

    //读取和记录zipkin的trace
    public ArrayList<ArrayList<ZipkinSpan>> getAndParseTraceZipkin(){
        String list = restTemplate.getForObject(zipkinQuery, String.class);
        Type founderListType = new TypeToken<ArrayList<ArrayList<ZipkinSpan>>>(){}.getType();
        return gson.fromJson(list, founderListType);
    }

    //读取和记录skywalking的trace
    public ArrayList<SimpleTrace> getAndParseTraceSkywalking(Date now){
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HashMap<String, Object> map = new HashMap<>();
        map.put("query", "query queryTraces($condition: TraceQueryCondition) {\n  data: queryBasicTraces(condition: $condition) {\n    traces {\n      key: segmentId\n      endpointNames\n      duration\n      start\n      isError\n      traceIds\n    }\n    total\n  }}");
        HashMap<String, Object> variablesMap = new HashMap<>();
        HashMap<String, Object> queryDuration = new HashMap<>();
        HashMap<String, Object> conditionMap = new HashMap<>();
        HashMap<String, Object> pagingMap = new HashMap<>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HHmmss");
        queryDuration.put("start", dateFormat.format(now.getTime()-8*60*60*1000-80000));
        queryDuration.put("end", dateFormat.format(now));
        queryDuration.put("step", "SECOND");
        int pageNum = 0;
        int total = 1;
        pagingMap.put("pageSize", 100);
        pagingMap.put("needTotal", true);

        conditionMap.put("queryDuration", queryDuration);
        conditionMap.put("queryOrder", "BY_START_TIME");
        conditionMap.put("traceState", "ALL");

        // 获取全部trace
        Data data = new Data();
        while(pageNum*100 < total) {
            pageNum++;
            pagingMap.put("pageNum", pageNum);
            conditionMap.put("paging", pagingMap);
            variablesMap.put("condition", conditionMap);
            map.put("variables", variablesMap);
            HttpEntity<HashMap<String, Object>> request = new HttpEntity<>(map, headers);
            String list = restTemplate.postForObject(skywalkingQuery, request, String.class);
            if(list != null)
                list = list.substring(8,list.length()-1);
//            System.out.println(list);
            DataOutside dataOutside = gson.fromJson(list, DataOutside.class);
            data = dataOutside.getData();
            total = data.getTotal();
        }
        // 获取全部trace的细节信息
        map.clear();
        map.put("query", "query queryTrace($traceId: ID!) {\n  trace: queryTrace(traceId: $traceId) {\n    spans {\n      traceId\n      segmentId\n      spanId\n      parentSpanId\n      refs {\n        traceId\n        parentSegmentId\n        parentSpanId\n        type\n      }\n      serviceCode\n      serviceInstanceName\n      startTime\n      endTime\n      endpointName\n      type\n      peer\n      component\n      isError\n      layer\n      tags {\n        key\n        value\n      }\n      logs {\n        time\n        data {\n          key\n          value\n        }\n      }\n    }\n  }\n  }");
        variablesMap.clear();
        for (int i = 0; i < data.getTraces().size(); i++){
            variablesMap.put("traceId", data.getTraces().get(i).getTraceIds().get(0));
            map.put("variables", variablesMap);
            HttpEntity<HashMap<String, Object>> request = new HttpEntity<>(map, headers);
            String list = restTemplate.postForObject(skywalkingQuery, request, String.class);
            if(list != null)
                list = list.substring(17,list.length()-2);
            data.getTraces().get(i).setTrace(gson.fromJson(list, Trace.class));
        }
        return data.getTraces();
    }

    //将Trace的各个Span解析出来并上传到图中 包括这个Trace经过的Pod和API
    //示例： Service A Pod 1 -> Service B Api 1 -> Service B Pod 1
    //注意：这个函数上传的是这个Trace 是针对单独trace而言的
    public void uploadEveryTraceZipkin(Date now){
        //1.获得所有的Trace
        ArrayList<ArrayList<ZipkinSpan>> traces = getAndParseTraceZipkin();
        //2.依次解析每一条trace
        for(ArrayList<ZipkinSpan> trace : traces){
            //这条trace如果已经被处理过的话 就不再处理了
            if(uploadedTraces.contains(trace.get(0).getTraceId())){
                continue;
            }
            //3.每一条trace会被解析成两个部分
            ArrayList<TraceInvokeApiToPod> traceApiToPod = new ArrayList<>();
            ArrayList<TraceInvokePodToApi> tracePodToApi = new ArrayList<>();

            getTraceInvokeInformationZipkin(trace, traceApiToPod, tracePodToApi);

            //3.两个部分分别上传
            try{
                if(!traceApiToPod.isEmpty()){
                    restTemplate.postForObject(neo4jDaoIP + "/traceApiToPod", traceApiToPod, traceApiToPod.getClass());
                }
                if(!tracePodToApi.isEmpty()){
                    restTemplate.postForObject(neo4jDaoIP + "/tracePodToApi", tracePodToApi, tracePodToApi.getClass());
                }
                uploadedTraces.add(trace.get(0).getTraceId());
                System.out.println("上传Trace " + trace.get(0).getTraceId());
            }catch (Exception e){
                e.printStackTrace();
            }

        }
        //4.完成
        System.out.println("Trace上传完成");
    }

    public void uploadEveryTraceSkywalking(Date now){
        //1.获得所有的Trace
        ArrayList<SimpleTrace> traces = getAndParseTraceSkywalking(now);
        //2.依次解析每一条trace
        for(SimpleTrace trace : traces){
            //这条trace如果已经被处理过的话 就不再处理了
            if(uploadedTraces.contains(trace.getTraceIds().get(0))){
                continue;
            }
            //如果这条trace是prometheus的也不处理
            String endpointName = trace.getEndpointNames().get(0);
            if(endpointName.endsWith("prometheus")){
                continue;
            }
            //3.每一条trace会被解析成三个部分：Pod->Exit API->Entry API->Pod(如果splitSpan为True）
            ArrayList<TraceInvokeApiToPod> traceApiToPod = new ArrayList<>();
            ArrayList<TraceInvokePodToApi> tracePodToApi = new ArrayList<>();
            ArrayList<TraceInvokeApiToApi> traceApiToApi = new ArrayList<>();

            getTraceInvokeInformationSkywalking(trace, traceApiToPod, tracePodToApi, traceApiToApi, Boolean.FALSE);

            //3.三个部分分别上传
            try{
                System.out.println(traceApiToPod.size() + " " + tracePodToApi.size() + " " + traceApiToApi.size());
                if(!traceApiToPod.isEmpty()){
                    restTemplate.postForObject(neo4jDaoIP + "/traceApiToPod", traceApiToPod, traceApiToPod.getClass());
                }
                if(!tracePodToApi.isEmpty()){
                    restTemplate.postForObject(neo4jDaoIP + "/tracePodToApi", tracePodToApi, tracePodToApi.getClass());
                }
                if(!traceApiToApi.isEmpty()){
                    restTemplate.postForObject(neo4jDaoIP + "/traceApiToApi", traceApiToApi, traceApiToApi.getClass());
                }
                uploadedTraces.add(trace.getTraceIds().get(0));
                System.out.println("上传Trace " + trace.getTraceIds().get(0));
            }catch (Exception e){
                e.printStackTrace();
            }

        }
        //4.完成
        System.out.println("Trace上传完成");
    }

    //从Trace中抽取API和Service之间的关系并上传
    //可以从Trace中抽取出API所属于哪个Service以及API被哪些Service调用过
    //这些信息的统计量将会被上传到图中
    //上传的是统计量而不是Trace本身
    public void uploadApiSvcRelationsZipkin(Date now){
        ArrayList<AppServiceHostServiceAPI> svcApiRelations = new ArrayList<>();
        ArrayList<AppServiceInvokeServiceAPI> svcInvokeApiRelations = new ArrayList<>();
        getServiceHostApiAndServiceInvokeApiZipkin(svcApiRelations, svcInvokeApiRelations, now);
        //向对面提交一堆并处理结果
        ArrayList<AppServiceHostServiceAPI> updatedSvcApiRelations = restTemplate.postForObject(
                neo4jDaoIP + "/apiHostService", svcApiRelations, svcApiRelations.getClass());
        ArrayList<AppServiceInvokeServiceAPI> updatedSvcInvokeApiRelations = restTemplate.postForObject(
                neo4jDaoIP + "/apiInvokeService", svcInvokeApiRelations, svcInvokeApiRelations.getClass());
        System.out.println("API->Host 数量:" + apis.size());
    }

    //从Trace中抽取API和Service之间的关系并上传
    //可以从Trace中抽取出API所属于哪个Service以及API被哪些Service调用过
    //这些信息的统计量将会被上传到图中
    //上传的是统计量而不是Trace本身
    public void uploadApiSvcRelationsSkywalking(Date now){
        ArrayList<AppServiceHostServiceAPI> svcApiRelations = new ArrayList<>();
        ArrayList<AppServiceInvokeServiceAPI> svcInvokeApiRelations = new ArrayList<>();
        getServiceHostApiAndServiceInvokeApiSkywalking(svcApiRelations, svcInvokeApiRelations, now);
        //向对面提交一堆并处理结果
        ArrayList<AppServiceHostServiceAPI> updatedSvcApiRelations = restTemplate.postForObject(
                neo4jDaoIP + "/apiHostService", svcApiRelations, svcApiRelations.getClass());
        ArrayList<AppServiceInvokeServiceAPI> updatedSvcInvokeApiRelations = restTemplate.postForObject(
                neo4jDaoIP + "/apiInvokeService", svcInvokeApiRelations, svcInvokeApiRelations.getClass());
        System.out.println("API->Host 数量:" + apis.size());
    }

    //提供一个Trace 从中抽取出API与Pod之间关系
    //抽取结果的容器也在参数中
    public void getTraceInvokeInformationZipkin(ArrayList<ZipkinSpan> trace, ArrayList<TraceInvokeApiToPod> traceApiToPod, ArrayList<TraceInvokePodToApi> tracePodToApi){
        //统计一个个的span对API的调用时长的和调用发起的时间的
        HashMap<String, TreeMap<Long, Double>> apiMetricsMap = new HashMap<>();

        //遍历一个trace的每一个span
        for(ZipkinSpan span : trace) {
            //istio的输出信息不是我们需要的 忽略
            if (span.getName().contains("istio-policy")) {
                continue;
            }
            // Client-Send或者Client-Receive才是我们需要的
            if (!span.getAnnotations().get(0).getValue().equals("cr") &&
                    !span.getAnnotations().get(0).getValue().equals("cs")) {
                continue;
            }
            //开始处理我们需要的内容
            //找到key=http.url的那个binary-annotation与key: "node_id" 从node_id中提取podId
            String api = "";
            String apiHostService = "";
            String podId = "";
            for(BinaryAnnotation bn : span.getBinaryAnnotations()){
                //不是http.url就跳过吧
                if(!"http.url".equals(bn.getKey()) && !"node_id".equals(bn.getKey())){
                    continue;
                }
                if("http.url".equals(bn.getKey())){
                    String totalInvokeAddress = bn.getValue();
                    apiHostService = getHostFromLink(totalInvokeAddress);
                    api = getApiFromLink(totalInvokeAddress);
                }
                if("node_id".equals(bn.getKey())){
                    String fullPodName = bn.getValue();
                    podId = fetchPodName(fullPodName);
                }
            }
            if(/**invokeService.equals(hostService) ||**/ apiHostService.contains("10.")){
                continue;
            }
            //将trace的两部分组装好
            //看下API在吗，不在的话重组一个
            //System.out.println("Trace复现过程中发现API " + api);
            Pod pod = pods.get(podId);
            if(pod == null){
                System.out.println("[意外情况]Pod找不到 此Trace将被跳过 PodID：" + podId);
                continue;
            }

            ServiceAPI serviceApi;
            if(apis.get(api) != null){
                serviceApi = apis.get(api);
            }else{
                serviceApi = new ServiceAPI();
                serviceApi.setHostName(apiHostService);
                serviceApi.setName(api);
                //API的ID就是API的名字
                serviceApi.setId(api);
                serviceApi.setLatestUpdateTimestamp(currTimestampString);
                serviceApi.setCreationTimestamp(currTimestampString);
                apis.put(serviceApi.getName(), serviceApi);
            }

            //这种情况下应该给创建一个API指向pod的连接
            if(podId.contains(apiHostService)){
                TraceInvokeApiToPod relation = new TraceInvokeApiToPod();
                relation.setId(serviceApi.getId() + "_" + pod.getId());
                relation.setPod(pod);
                relation.setServiceAPI(serviceApi);
                relation.setRelation("TRACE");

                HashSet<String> passingTracesAndSpans = new HashSet<>();
                passingTracesAndSpans.add(span.getTraceId() + "-" + span.getId());
                relation.setTraceIdAndSpanIds(passingTracesAndSpans);

                traceApiToPod.add(relation);
            }else{
            //这种情况下应该创建一个POD指向API的连接
                TraceInvokePodToApi relation = new TraceInvokePodToApi();
                relation.setId(pod.getId() + "_" + serviceApi.getId());
                relation.setPod(pod);
                relation.setServiceAPI(serviceApi);
                relation.setRelation("TRACE");

                HashSet<String> passingTracesAndSpans = new HashSet<>();
                passingTracesAndSpans.add(span.getTraceId() + "-" + span.getId());

                //Metric - duration处理api调用时间长度的问题
                //Metric -> Api
                TreeMap<Long, Double> apiTimeMetricMap = apiMetricsMap.getOrDefault(serviceApi.getName(), new TreeMap<>());
                apiTimeMetricMap.put(Long.parseLong(span.getTimestamp()), Double.parseDouble(span.getDuration()));
                apiMetricsMap.put(serviceApi.getName(), apiTimeMetricMap);

                relation.setTraceIdAndSpanIds(passingTracesAndSpans);

                tracePodToApi.add(relation);
            }

        }

        //处理一下span响应时间的问题
        handleApiMetrics(apiMetricsMap);
    }

    //提供一个Trace 从中抽取出API与Pod之间关系
    //抽取结果的容器也在参数中
    //如果SplitApi为True则调用分为3阶段pod->exit API->entry API ->pod,否则为2阶段
    public void getTraceInvokeInformationSkywalking(SimpleTrace trace, ArrayList<TraceInvokeApiToPod> traceApiToPod, ArrayList<TraceInvokePodToApi> tracePodToApi, ArrayList<TraceInvokeApiToApi> traceApiToApi, Boolean splitApi){
        //统计一个个的span对API的调用时长的和调用发起的时间的
//        HashMap<String, TreeMap<Long, Double>> apiMetricsMap = new HashMap<>();
        HashMap<String, ServiceAPI> spanIdAPI = new HashMap<>();
        HashMap<String, Integer> spanIdTraceInvokePodToApi = new HashMap<>();
        //遍历一个trace的每一个span
        for(SkywalkingSpan span : trace.getTrace().getSpans()) {
            //开始处理我们需要的内容
            //找到key=http.url的那个binary-annotation与key: "node_id" 从node_id中提取podId
            String api;
            if(span.getEndpointName().startsWith("MongoDB"))
                api = span.getServiceCode() + ":" + span.getEndpointName();
            else
                api = span.getEndpointName();

            String apiHostService = span.getServiceCode();
            String serviceIntanceName = span.getServiceInstanceName();
            String podIP = serviceIntanceName.substring(serviceIntanceName.indexOf('@')+1);

            //将trace的两部分组装好
            //看下API在吗，不在的话重组一个
            //System.out.println("Trace复现过程中发现API " + api);
            Pod pod = IPpods.get(podIP);
            if(pod == null){
                System.out.println("[意外情况]Pod找不到 此Trace将被跳过 PodIP：" + podIP);
                continue;
            }

            ServiceAPI serviceApi;
            if(apis.get(api) != null){
                serviceApi = apis.get(api);
            }else{
                serviceApi = new ServiceAPI();
                serviceApi.setHostName(apiHostService);
                serviceApi.setName(api);
                //API的ID就是API的名字
                serviceApi.setId(api);
                serviceApi.setLatestUpdateTimestamp(currTimestampString);
                serviceApi.setCreationTimestamp(currTimestampString);
                apis.put(serviceApi.getName(), serviceApi);
            }
            spanIdAPI.put(span.getTraceId() + "." + span.getSegmentId() + "." + span.getSpanId(), serviceApi);


            if(span.getType().equals("Exit")){
                //创建一个Pod指向exit API的连接
                TraceInvokePodToApi traceInvokePodToApi = new TraceInvokePodToApi();
                traceInvokePodToApi.setId(pod.getId() + "_" + serviceApi.getId());
                traceInvokePodToApi.setPod(pod);
                traceInvokePodToApi.setServiceAPI(serviceApi);
                traceInvokePodToApi.setRelation("TRACE");

                HashSet<String> passingTracesAndSpans = new HashSet<>();
                passingTracesAndSpans.add(span.getTraceId() + "." + span.getSegmentId() + "." + span.getSpanId());

                //Metric - duration处理api调用时间长度的问题
                //Metric -> Api
//                TreeMap<Long, Double> apiTimeMetricMap = apiMetricsMap.getOrDefault(serviceApi.getName(), new TreeMap<>());
//                apiTimeMetricMap.put(span.getStartTime(), (double)(span.getEndTime()-span.getStartTime()));
//                apiMetricsMap.put(serviceApi.getName(), apiTimeMetricMap);
                traceInvokePodToApi.setTraceIdAndSpanIds(passingTracesAndSpans);
                tracePodToApi.add(traceInvokePodToApi);
                spanIdTraceInvokePodToApi.put(span.getTraceId() + "." + span.getSegmentId() + "." + span.getSpanId(), tracePodToApi.size()-1);
            }else if(span.getType().equals("Entry")){
                //创建一个Entry API指向pod的连接
                TraceInvokeApiToPod traceInvokeApiToPod = new TraceInvokeApiToPod();
                traceInvokeApiToPod.setId(serviceApi.getId() + "_" + pod.getId());
                traceInvokeApiToPod.setPod(pod);
                traceInvokeApiToPod.setServiceAPI(serviceApi);
                traceInvokeApiToPod.setRelation("TRACE");

                HashSet<String> passingTracesAndSpans = new HashSet<>();
                passingTracesAndSpans.add(span.getTraceId() + "." + span.getSegmentId() + "." + span.getSpanId());
                traceInvokeApiToPod.setTraceIdAndSpanIds(passingTracesAndSpans);
                traceApiToPod.add(traceInvokeApiToPod);

                //Metric - duration处理api调用时间长度的问题
                //Metric -> Api
//                TreeMap<Long, Double> apiTimeMetricMap = apiMetricsMap.getOrDefault(serviceApi.getName(), new TreeMap<>());
//                apiTimeMetricMap.put(span.getStartTime(), (double)(span.getEndTime()-span.getStartTime()));
//                apiMetricsMap.put(serviceApi.getName(), apiTimeMetricMap);

                // 创建一个Exit API指向Entry API的连接
                if(!span.getRefs().isEmpty()) {
                    String parentSpan = span.getRefs().get(0).getTraceId() + "." + span.getRefs().get(0).getParentSegmentId() + "." + span.getRefs().get(0).getParentSpanId();
                    if(splitApi) {
                        TraceInvokeApiToApi traceInvokeApiToApi = new TraceInvokeApiToApi();
                        traceInvokeApiToApi.setId(spanIdAPI.get(parentSpan).getId() + "_" + serviceApi.getId());
                        traceInvokeApiToApi.setServiceAPIFrom(spanIdAPI.get(parentSpan));
                        traceInvokeApiToApi.setServiceAPITo(serviceApi);
                        traceInvokeApiToApi.setRelation("TRACE");
                        traceApiToApi.add(traceInvokeApiToApi);
                    }else{
                        //修改PodToApi
                        TraceInvokePodToApi traceInvokePodToApi = tracePodToApi.get(spanIdTraceInvokePodToApi.get(parentSpan));
                        traceInvokePodToApi.getServiceAPI().setName(api);
                        traceInvokePodToApi.getServiceAPI().setId(api);
                        traceInvokePodToApi.getServiceAPI().setHostName(apiHostService);
                        traceInvokePodToApi.getServiceAPI().setLatestUpdateTimestamp(currTimestampString);
                    }
                }
            }

        }

        //处理一下span响应时间的问题
//        handleApiMetrics(apiMetricsMap);
    }

    private void handleApiMetrics(HashMap<String, TreeMap<Long, Double>> apiMetricsMap){

        System.out.println("=====handleApiMetrics=====");

        ArrayList<ServiceApiAndMetric> relations = new ArrayList<>();

        ArrayList<String> apiMetricIds = new ArrayList<>();

        for(String serviceApiName : apiMetricsMap.keySet()){

            TreeMap<Long, Double> apiTimeMetricMap = apiMetricsMap.get(serviceApiName);


            ArrayList<Long> sortedTimeList = new ArrayList<>();
            ArrayList<Double> sortedValueList = new ArrayList<>();

            for (Map.Entry<Long, Double> entry : apiTimeMetricMap.entrySet()) {
                sortedTimeList.add(entry.getKey());
                sortedValueList.add(entry.getValue());
            }

            ServiceApiMetric apiMetric = new ServiceApiMetric();
            apiMetric.setHistoryValues(sortedValueList);
            apiMetric.setHistoryTimestamps(sortedTimeList);
            apiMetric.setCreationTimestamp(getCurrentTimestamp());
            apiMetric.setLatestUpdateTimestamp(getCurrentTimestamp());
            apiMetric.setName(serviceApiName + "_" + "duration");
            apiMetric.setId(serviceApiName + "_" + "duration");

            apiMetricIds.add(apiMetric.getId());

            ServiceAPI serviceApi = apis.get(serviceApiName);

            ServiceApiAndMetric relation = new ServiceApiAndMetric();

            relation.setApiMetric(apiMetric);
            relation.setServiceAPI(serviceApi);
            relation.setRelation("SERVICEAPI_RUNTIME_INFO");
            relation.setId(apiMetric.getId() + "MetricAndServiceApi" + serviceApi.getId());

            System.out.println("==" + relation.getId());
            System.out.println("==" + relation.getApiMetric().getHistoryTimestamps().toString());
            System.out.println("==" + relation.getApiMetric().getHistoryValues().toString());

            relations.add(relation);
        }

        if(relations.isEmpty()){
            System.out.println("本Trace无Metric数据上传");
        }else{
            //向对方上传数据
            restTemplate.postForObject(neo4jDaoIP + "/serviceApiMetrics", relations, relations.getClass());
        }

//        restTemplate.postForObject(graphAppIp + "/abnormality/apiList", apiMetricIds, String.class);
    }



    //收集所有的Trace 解析服务及其API 以及服务与API的调用关系
    //抽取结果放进了参数中提供的容器中
    public void getServiceHostApiAndServiceInvokeApiZipkin(ArrayList<AppServiceHostServiceAPI> svcHostApi,
                                                     ArrayList<AppServiceInvokeServiceAPI> svcInvokeApi,
                                                     Date now){
        //获取trace
        ArrayList<ArrayList<ZipkinSpan>> traces = getAndParseTraceZipkin();
        System.out.println("Trace数量:" + traces.size());
        //遍历每一个trace
        for(ArrayList<ZipkinSpan> trace : traces){

            if(tracesRecord.contains(trace.get(0).getTraceId())){
                continue;
            }else{
                tracesRecord.add(trace.get(0).getTraceId());
            }
            //遍历一个trace的每一个span
            for(ZipkinSpan span : trace){
                //istio的输出信息不是我们需要的 忽略
                if(span.getName().contains("istio-policy")){
                    continue;
                }
                // Client-Send或者Client-Receive才是我们需要的
                if(!span.getAnnotations().get(0).getValue().equals("cr") &&
                        !span.getAnnotations().get(0).getValue().equals("cs")){
                    continue;
                }
                //开始处理我们需要的内容
                //找到key=http.url的那个binary-annotation
                for(BinaryAnnotation bn : span.getBinaryAnnotations()){
                    //不是http.url就跳过吧
                    if(!"http.url".equals(bn.getKey())){
                        continue;
                    }
                    String invokeSource = bn.getEndpoint().getServiceName();
                    String totalInvokeAddress = bn.getValue();
                    //解析出API所在的服务名,调用API的服务名以及API本身的名称
                    String invokeService = getSvcNameFromTotalName(invokeSource);
                    String hostService = getHostFromLink(totalInvokeAddress);
                    String api = getApiFromLink(totalInvokeAddress);
                    //开头是ip的不要 服务指向自己的也不要
                    if(invokeService.equals(hostService) || hostService.contains("10.")){
                        continue;
                    }
                    //看下API在吗，不在的话重组一个
                    ServiceAPI serviceApi;
                    if(apis.get(api) != null){
                        serviceApi = apis.get(api);
                        serviceApi.setLatestUpdateTimestamp(currTimestampString);
                    }else{
                        serviceApi = new ServiceAPI();
                        serviceApi.setHostName(hostService);
                        serviceApi.setName(api);
                        //API的ID就是API的名字
                        serviceApi.setId(api);
                        serviceApi.setLatestUpdateTimestamp(currTimestampString);
                        serviceApi.setCreationTimestamp(currTimestampString);
                        apis.put(serviceApi.getName(), serviceApi);
                    }

                    //看看host serivice在吗 不在的话就不管了 在的话组装一下relation
                    if(svcs.get(hostService)!= null){
                       AppService hostSvc = svcs.get(hostService);
                       AppServiceHostServiceAPI relationHost = new AppServiceHostServiceAPI();
                       relationHost.setAppService(hostSvc);
                       relationHost.setServiceAPI(serviceApi);
                       relationHost.setId(serviceApi.getId() + "ApiSvc" + hostSvc.getId());
                       relationHost.setRelation("API_HOST_ON");
                       svcHostApi.add(relationHost);
                    }

                    //看看invoke service在吗 不在的话就不管了 在的话组装一下relation
                    if(svcs.get(invokeService)!= null){
                        AppService invokeSvc = svcs.get(invokeService);
                        AppServiceInvokeServiceAPI relationInvoke = new AppServiceInvokeServiceAPI();
                        relationInvoke.setAppService(invokeSvc);
                        relationInvoke.setServiceAPI(serviceApi);
                        relationInvoke.setCount(1);
                        relationInvoke.setId(serviceApi.getId() + "ApiSvc" + invokeSvc.getId());
                        relationInvoke.setRelation("API_INVOKE_BY");
                        svcInvokeApi.add(relationInvoke);
                    }
                }
            }
        }
    }


    //收集所有的Trace 解析服务及其API 以及服务与API的调用关系
    //抽取结果放进了参数中提供的容器中
    public void getServiceHostApiAndServiceInvokeApiSkywalking(ArrayList<AppServiceHostServiceAPI> svcHostApi,
                                                           ArrayList<AppServiceInvokeServiceAPI> svcInvokeApi,
                                                           Date now){
        //获取trace
        ArrayList<SimpleTrace> traces = getAndParseTraceSkywalking(now);
        System.out.println("Trace数量:" + traces.size());
        //遍历每一个trace
        for(SimpleTrace trace : traces){

            if(tracesRecord.contains(trace.getTraceIds().get(0))){
                continue;
            }else{
                tracesRecord.add(trace.getTraceIds().get(0));
            }
            //遍历一个trace的每一个span
            for(SkywalkingSpan span : trace.getTrace().getSpans()){
                //开始处理我们需要的内容
                //找到key=http.url的那个binary-annotation
                if(span.getType().equals("Exit")){

                    //解析出API所在的服务名,调用API的服务名以及API本身的名称
                    String invokeService = span.getPeer();
                    String hostService = span.getServiceCode();
                    String api = span.getEndpointName();
                    //开头是ip的不要 服务指向自己的也不要
                    if(invokeService.equals(hostService)){
                        continue;
                    }
                    //看下API在吗，不在的话重组一个
                    ServiceAPI serviceApi;
                    if(apis.get(api) != null){
                        serviceApi = apis.get(api);
                        serviceApi.setLatestUpdateTimestamp(currTimestampString);
                    }else{
                        serviceApi = new ServiceAPI();
                        serviceApi.setHostName(hostService);
                        serviceApi.setName(api);
                        //API的ID就是API的名字
                        serviceApi.setId(api);
                        serviceApi.setLatestUpdateTimestamp(currTimestampString);
                        serviceApi.setCreationTimestamp(currTimestampString);
                        apis.put(serviceApi.getName(), serviceApi);
                    }

                    //看看host serivice在吗 不在的话就不管了 在的话组装一下relation
                    if(svcs.get(hostService)!= null){
                        AppService hostSvc = svcs.get(hostService);
                        AppServiceHostServiceAPI relationHost = new AppServiceHostServiceAPI();
                        relationHost.setAppService(hostSvc);
                        relationHost.setServiceAPI(serviceApi);
                        relationHost.setId(serviceApi.getId() + "ApiSvc" + hostSvc.getId());
                        relationHost.setRelation("API_HOST_ON");
                        svcHostApi.add(relationHost);
                    }

                    //看看invoke service在吗 不在的话就不管了 在的话组装一下relation
                    if(svcs.get(invokeService)!= null){
                        AppService invokeSvc = svcs.get(invokeService);
                        AppServiceInvokeServiceAPI relationInvoke = new AppServiceInvokeServiceAPI();
                        relationInvoke.setAppService(invokeSvc);
                        relationInvoke.setServiceAPI(serviceApi);
                        relationInvoke.setCount(1);
                        relationInvoke.setId(serviceApi.getId() + "ApiSvc" + invokeSvc.getId());
                        relationInvoke.setRelation("API_INVOKE_BY");
                        svcInvokeApi.add(relationInvoke);
                    }
                }
            }
        }
    }


    //从一个链接中提取API名称
    private String getApiFromLink(String url){
        return MatcherUrlRouterUtil.matcherPattern(url);
    }

    //从一个链接中提取Host服务的名称
    private String getHostFromLink(String url){
        String api = MatcherUrlRouterUtil.matcherPattern(url);
        int index1 = url.indexOf("http://");
        int index2 = url.indexOf(api);
        String svc = url.substring(index1, index2).substring("http://".length());
        int index3 = svc.indexOf(":");
        svc = svc.substring(0, index3);
        return svc;
    }

    //从服务的全限定名中提取服务的名称
    private String getSvcNameFromTotalName(String s){
        int index = s.indexOf(".");
        return s.substring(0, index);
    }


    //更新所有metrics
    //更新依据是所有记录在案的container
    //如果要依据最新的container需要先更新containers
    public ArrayList<Metric> updateMetrics(){
        ArrayList<Metric> newMetrics = new ArrayList<>();
        //遍历需要取的指标数据
        for(String containerMetricName : containerMetricsNameVector){
            //每个container都需要取这些数据
            for(Container container : containers.values()){
                String containerName = container.getName();
                if(containerName.startsWith("/")){
                    containerName = containerName.substring(1);
                }
                try{
                    ExpressionQueriesVectorResponse res = getMetric(containerMetricName, containerName);
                    Metric metric = getMetricFromExpressionQueriesVectorResponse(res,
                            containerMetricName, containerName);
                    metric.setLatestUpdateTimestamp(currTimestampString);
                    newMetrics.add(metric);

                }catch (Exception e){
                    System.out.println("[错误]未查到此容器的此Metric 容器名称:" + containerName + " Metric名称:" +containerMetricName);
                }
            }
        }
        ArrayList<Metric> updatedMetrics = restTemplate.postForObject(
                neo4jDaoIP + "/updateMetrics", newMetrics, newMetrics.getClass());
        return updatedMetrics;
    }

    //向Promethsus发送并取数据
    //container_memory_usage_bytes{name="k8s_ts-order-service_ts-order-service-68d9c9b878-vgzhl_default_ff88298e-777c-11e9-bb23-005056a4ea84_26"}
    private ExpressionQueriesVectorResponse getMetric(String metricName, String containerName){

        String queryStr = metricName + "{" + "name=" + "\"" + containerName + "\"" + "}";
        MultiValueMap<String, Object> postParameters = new LinkedMultiValueMap<>();
        postParameters.add("query", queryStr);
        HashMap<String, String> getParameters = new HashMap<>();
        getParameters.put("query", queryStr);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/x-www-form-urlencoded");
        HttpEntity<MultiValueMap<String, Object>> r = new HttpEntity<>(postParameters, headers);
        String str = restTemplate.getForObject(promethsusQuery + "?query={query}", String.class, getParameters);
//        System.out.println(str);
        if(str.contains("\"resultType\":\"vector\"")){
            ExpressionQueriesVectorResponse res = gson.fromJson(str,ExpressionQueriesVectorResponse.class);

            return res;
        }else{
            System.out.println("[错误]Promethsus数据不合规 无法解析");
            return null;
        }

    }


    public ContainerList getContainerList(){
        ArrayList<ApiContainer> containers = new ArrayList<>();
        for(String clusterIP : clusterIPs) {

            String list = restTemplate.getForObject(clusterIP + ":5678/containers/json", String.class);

            JsonParser parser = new JsonParser();
            JsonArray jsonArray = parser.parse(list).getAsJsonArray();
            Gson gson = new Gson();

            ArrayList<ApiContainer> tempList = new ArrayList<>();
            //加强for循环遍历JsonArray
            for (JsonElement user : jsonArray) {
                //使用GSON，直接转成Bean对象
                ApiContainer apiContainer = gson.fromJson(user, ApiContainer.class);
                tempList.add(apiContainer);
            }
            containers.addAll(tempList);
        }
        ContainerList containerList = new ContainerList();
        containerList.setItems(containers);
        return containerList;
    }

    public ArrayList<AlertRule> getAlertRules(){
        //获取prometheus Alert页面的html进行爬虫解析
        String html = restTemplate.getForObject(promethsusQuery.substring(0,promethsusQuery.length()-12) + "alerts",String.class);
        Document document = Jsoup.parse(html);
        Elements divs = document.getElementsByTag("div");
        ArrayList<AlertRule> alertRules = new ArrayList<>();
        for(Element div: divs){
            if(!div.text().startsWith("alert: "))
                continue;
            //一行行处理
            AlertRule alertRule = new AlertRule();
            for(String line: div.text().split("\n")){
                if(line.startsWith("alert"))
                    alertRule.setName(line.substring(7));
                else if(line.startsWith("expr"))
                    alertRule.setExpr(line.substring(6));
                else if(line.startsWith("for"))
                    alertRule.setDuration(line.substring(5));
                else if(line.startsWith("  severity"))
                    alertRule.setSeverity(line.substring(12));
            }
            alertRule.setId(alertRule.getName() + "_Severity_" + alertRule.getSeverity());
            //处理metric：剔除{}、[]部分，剔除保留字irate、rate，剔除字符
            String expr = alertRule.getExpr();
            String metricName;
            int start = 0;
            for(int i=0;i<expr.length();i++){
                if(expr.charAt(i)=='i'&&expr.substring(i,Math.min(i+6, expr.length())).equals("irate(")){
                    i+=5;
                    start = i + 1;
                }else if(expr.charAt(i)=='r'&&expr.substring(i,Math.min(i+5, expr.length())).equals("rate(")){
                    i+=4;
                    start = i + 1;
                }else if(!(expr.charAt(i)>='a'&&expr.charAt(i)<='z')&&!(expr.charAt(i)>='A'&&expr.charAt(i)<='Z')&&!(expr.charAt(i)=='_')&&!(expr.charAt(i)>='0'&&expr.charAt(i)<='9')){
                    //既不是小写字母，又不是大写字母，也不是_，也不是数字
                    if(start!=i) {
                        metricName = expr.substring(start, i);
                        // 纯数字不是指标名称
                        boolean isPureDigit = true;
                        for(int j=0;j<metricName.length();j++){
                            if(metricName.charAt(j)<'0'||metricName.charAt(j)>'9'){
                                isPureDigit = false;
                                break;
                            }
                        }
                        if(!isPureDigit) {
                            alertRule.getMetric().add(new MetricSearch());
                            alertRule.getMetric().get(alertRule.getMetric().size()-1).setName(metricName);
                        }
                    }
                    if(expr.charAt(i)=='{'){
                        int end = expr.indexOf('}', i);
                        String[] searchkeyToValue = expr.substring(i+1, end).split("=|,|!~");
                        HashMap<String, String> search = new HashMap<>();
                        for(int j=0;j<searchkeyToValue.length;j+=2){
                            search.put(searchkeyToValue[j], searchkeyToValue[j+1]);
                        }
                        alertRule.getMetric().get(alertRule.getMetric().size()-1).setSearchKeyToValue(search);
                        i = end;
                        start = i + 1;
                    }else if(expr.charAt(i)=='['){
                        i = expr.indexOf(']', i);
                        start = i + 1;
                    }
                    else
                        start = i + 1;
                }
            }
            if(start!=expr.length()){
                metricName = expr.substring(start);
                // 纯数字不是指标名称
                boolean isPureDigit = true;
                for(int j=0;j<metricName.length();j++){
                    if(metricName.charAt(j)<'0'||metricName.charAt(j)>'9'){
                        isPureDigit = false;
                        break;
                    }
                }
                if(!isPureDigit) {
                    alertRule.getMetric().add(new MetricSearch());
                    alertRule.getMetric().get(alertRule.getMetric().size()-1).setName(metricName);
                }
            }
            alertRules.add(alertRule);
        }
        return alertRules;
    }


    public NodeList getNodeList(){
        String list = restTemplate.getForObject( masterIP + "/api/v1/nodes", String.class);
        NodeList nodeList = gson.fromJson(list,NodeList.class);
        return nodeList;
    }

    public PodList getPodList(){
        String list = restTemplate.getForObject(masterIP + "/api/v1/pods", String.class);
        PodList podList = gson.fromJson(list, PodList.class);
        return podList;
    }

    public AppServiceList getAppServiceList(){
        String list = restTemplate.getForObject(masterIP + "/api/v1/services", String.class);
        AppServiceList appServiceList = gson.fromJson(list, AppServiceList.class);
        return appServiceList;
    }

    private void clearAllInfo(){
        vms.clear();
        svcs.clear();
        pods.clear();
        containers.clear();
    }

    //构建一个基础的知识图谱 - 包括pod node svc
    public String createRawFrameworkToKnowledgeGraph(){
        //清空环境
        clearAllInfo();
        System.out.println("Clear");
        //第一步: 获取所有的node,pod,service
        ArrayList<ApiNode> apiNodeList = getNodeList().getItems();
        System.out.println("Get Nodes");
        ArrayList<ApiPod> apiPodList = getPodList().getItems();
        System.out.println("Get POD");
        ArrayList<ApiAppService> apiServiceList = getAppServiceList().getItems();
        System.out.println("Get Service");
        ArrayList<ApiContainer> apiContainerList = getContainerList().getItems();
        System.out.println("Get Container");
        //获取告警规则
        ArrayList<AlertRule> alertRuleList = getAlertRules();
        System.out.println("Get AlertRules");

        //第二步: 构建关系
        ArrayList<VirtualMachineAndPod> vmPodRelations = constructVmPodRelation(apiNodeList,apiPodList);
        System.out.println("vmPodRelations");
        ArrayList<AppServiceAndPod> appServiceAndPodRelations = constructAppServicePodRelation(apiServiceList, apiPodList);
        System.out.println("appServiceAndPodRelations");
        ArrayList<PodAndContainer> podAndContainerRelations = constructPodAndContainerRelation(apiPodList,apiContainerList);
        System.out.println("podAndContainerRelations");

        //第三步: 上传关系(无需额外上传entity, 关系中包含entity, 对面会自动处理)
        ArrayList<VirtualMachineAndPod> vmPodRelationsResult;
        vmPodRelationsResult = postVmAndPodList(vmPodRelations);
        System.out.println("完成上传VirtualMachineAndPod:" + vmPodRelationsResult.size());

        ArrayList<AppServiceAndPod> appServiceAndPodsResult;
        appServiceAndPodsResult = postSvcAndPodList(appServiceAndPodRelations);
        System.out.println("完成上传AppServiceAndPod:" + appServiceAndPodsResult.size());

        ArrayList<PodAndContainer> podAndContainerResult;
        podAndContainerResult = postPodAndContainerList(podAndContainerRelations);
        System.out.println("完成上传PodAndContainer:" + podAndContainerResult.size());

        //先获取Prometheus拥有的所有指标
        getAllMetricsName();
        //构建Metric与其他节点的关系
        ArrayList<PodAndMetric> podAndMetrics = new ArrayList<>();
        ArrayList<MetricAndContainer> metricAndContainers = new ArrayList<>();
        ArrayList<MetricAndVirtualMachine> metricAndVirtualMachines = new ArrayList<>();
        constructMetricAndOthers(podAndMetrics, metricAndContainers, metricAndVirtualMachines);
        System.out.println("Pod与metric关系共" + podAndMetrics.size() + "个");
        System.out.println("Metric与Container关系共" + metricAndContainers.size() + "个");
        System.out.println("Metric与节点（虚拟机）关系共" + metricAndVirtualMachines.size() + "个");
//        ArrayList<MetricAndContainer> metricAndContainerRelations = constructMetricAndContainer(apiContainerList);
        System.out.println("metricAndOthersRelations");
        //构建Metric与告警规则的关系
        ArrayList<MetricAndAlertRule> metricAndAlertRules = constructMetricAndAlertRules(alertRuleList);
        System.out.println("metricAndAlertRulesRelations");

        ArrayList<MetricAndContainer> metricAndContainerResult = postMetricAndContainerList(metricAndContainers);
        ArrayList<PodAndMetric> podAndMetricArrayList = postPodAndMetricList(podAndMetrics);
        ArrayList<MetricAndVirtualMachine> metricAndVirtualMachineResult = postMetricAndVirtualMachine(metricAndVirtualMachines);
//        metricAndContainerResult = postMetricAndContainerList(metricAndContainerRelations);

        System.out.println("完成上传MetricAndContainer:" + metricAndContainerResult.size());
        System.out.println("完成上传MetricAndPod:" + podAndMetricArrayList.size());
        System.out.println("完成上传MetricAndVirtualMachine:" + metricAndVirtualMachineResult.size());

        ArrayList<MetricAndAlertRule> metricAndAlertRuleArrayList = postMetricAndAlertRule(metricAndAlertRules);
        System.out.println("完成上传MetricAndAlertRule:" + metricAndAlertRuleArrayList.size());
        System.out.println("虚拟机数量:" + vms.size());
        System.out.println("服务数量:" + svcs.size());
        System.out.println("Pod数量:" + pods.size());
        System.out.println("容器数量:" + containers.size());
        return "";
    }

    public void getAllMetricsName(){
        String url = promethsusQuery.substring(0, promethsusQuery.length()-5) + "label/__name__/values?_=1657276036988";
        String res = restTemplate.getForObject(url, String.class);
        ExpressionQueriesLabelsResponse expressionQueriesLabelsResponse = gson.fromJson(res, ExpressionQueriesLabelsResponse.class);
        metricsName = expressionQueriesLabelsResponse.getData();
    }

    public ArrayList<MetricAndAlertRule> constructMetricAndAlertRules(ArrayList<AlertRule> alertRules){
        //避免重复的关系
        HashSet<String> relations = new HashSet<>();
        ArrayList<MetricAndAlertRule> metricAndAlertRules = new ArrayList<>();
        //获取ApiServer, Prometheus的Pod
        String apiserverPodName = null, prometheusPodName = null;
        for(String key: pods.keySet()){
            if(key.startsWith("kube-apiserver")){
                apiserverPodName = key;
            }
            if(key.startsWith("prometheus")){
                prometheusPodName = key;
            }
        }
        //根据告警规则寻找告警规则相应的指标
        for(AlertRule alertRule: alertRules){
            for(MetricSearch metricSearch: alertRule.getMetric()){
                StringBuilder query = new StringBuilder(metricSearch.getName() + "{");
                if(metricSearch.getSearchKeyToValue().containsKey("job"))
                    query.append("job=").append(metricSearch.getSearchKeyToValue().get("job")).append(",");
                if(metricSearch.getSearchKeyToValue().containsKey("instance"))
                    query.append("instance=").append(metricSearch.getSearchKeyToValue().get("instance")).append(",");
                if(metricSearch.getSearchKeyToValue().containsKey("pod"))
                    query.append("pod=").append(metricSearch.getSearchKeyToValue().get("pod")).append(",");
                if(metricSearch.getSearchKeyToValue().containsKey("name"))
                    query.append("name=").append(metricSearch.getSearchKeyToValue().get("name")).append(",");
                query.append("}");
                try {
                    String str = restTemplate.getForObject(promethsusQuery + "?query={query}", String.class, query);
                    if (str.contains("\"resultType\":\"vector\"")) {
                        ExpressionQueriesVectorResponse expressionQueriesVectorResponse = gson.fromJson(str, ExpressionQueriesVectorResponse.class);
                        for(ResultVector resultVector: expressionQueriesVectorResponse.getData().getResult()){
                            if(resultVector.getMetric().containsKey("job")){
                                String metricId = null;
                                if(resultVector.getMetric().get("job").equals("kubernetes-apiservers")){
                                    //全部和kube-apiserver的Pod关联
                                    if(apiserverPodName!=null){
                                        metricId = metricSearch.getName() + "_" + apiserverPodName;
                                    }
                                }else if(resultVector.getMetric().get("job").equals("kubernetes-nodes-kubelet")){
                                    //只有和主机相连的情况
                                    metricId = metricSearch.getName() + "_" + resultVector.getMetric().get("instance");

                                }else if(resultVector.getMetric().get("job").equals("kubernetes-nodes-cadvisor")){
                                    //可能和主机相连也可能和容器连接
                                    if(!resultVector.getMetric().containsKey("pod")||resultVector.getMetric().get("pod").equals("")){
                                        metricId = metricSearch.getName() + "_" + resultVector.getMetric().get("instance");
                                    }else{
                                        if(resultVector.getMetric().get("name").equals("")){
                                            //和pod连
                                            metricId = metricSearch.getName() + "_" + resultVector.getMetric().get("pod");
                                        }else{
                                            //和容器连
                                            metricId = metricSearch.getName() + "_" + resultVector.getMetric().get("name");
                                        }
                                    }
                                }else if(resultVector.getMetric().get("job").equals("kubernetes-service-endpoints")){
                                    //和Pod连，instance为pod IP
                                    String instance = resultVector.getMetric().get("instance");
                                    String IP = instance.substring(0, instance.indexOf(":"));
                                    if(IPpods.containsKey(IP)){
                                        //指标与该pod相连
                                        metricId = metricSearch.getName() + "_" + IPpods.get(IP).getId();
                                    }else{
                                        System.out.println("Job kubernetes-service-endpoints's instance " + IP + " doesn't exist.");
                                    }
                                }else if(resultVector.getMetric().get("job").equals("prometheus")){
                                    //只和prometheus的Pod相连
                                    if(prometheusPodName!=null) {
                                        metricId = metricSearch.getName() + "_" + prometheusPodName;

                                    }
                                }else if(resultVector.getMetric().get("job").equals("kubernetes-nodes")){
                                    String instance = resultVector.getMetric().get("instance");
                                    String IP = instance.substring(0, instance.indexOf(":"));
                                    //与主机连接
                                    metricId = metricSearch.getName() + "_" + IPToVM.get(IP).getId();
                                }else if(resultVector.getMetric().get("job").equals("kubernetes-pods")){
                                    //与pod相连
                                    String podName = resultVector.getMetric().get("kubernetes_pod_name");
                                    metricId = metricSearch.getName() + "_" + podName;
                                }
                                String relationId = alertRule.getId() + "_" + metricId;
                                if(!relations.contains(relationId)) {
                                    relations.add(relationId);
                                    metricAndAlertRules.add(new MetricAndAlertRule(metrics.get(metricId), alertRule, alertRule.getId() + "_AlertRuleAndMetric_" + metricId, "Alert_INFO"));
                                }
                            }else{
                                System.out.println("Metric: "+ metricSearch.getName() + "doesn't belong to any job.");
                            }
                        }
                    }else{
                        System.out.println("[错误]Promethesus数据不合规 无法解析");
                    }
                }catch (Exception e){
                    System.out.println("[错误]未查到此Metric Metric名称:" + metricSearch.getName());
                }
            }
        }
        return metricAndAlertRules;
    }

    public void constructMetricAndOthers(ArrayList<PodAndMetric> podAndMetrics, ArrayList<MetricAndContainer> metricAndContainers, ArrayList<MetricAndVirtualMachine> metricAndVirtualMachines){
        //甄别metric是否已经查询过
        HashSet<String> relations = new HashSet<>();
        //获取ApiServer, Prometheus的Pod
        Pod apiserverPod = null, prometheusPod = null;
        for(String key: pods.keySet()){
            if(key.startsWith("kube-apiserver")){
                apiserverPod = pods.get(key);
            }
            if(key.startsWith("prometheus")){
                prometheusPod = pods.get(key);
            }
        }
        //获取节点IP与节点的对应关系
        for(String key: vms.keySet()){
            IPToVM.put(vms.get(key).getAddress(), vms.get(key));
        }

        if(apiserverPod==null)
            System.out.println("Pod: kube-apiserver找不到，没有指标可以与其对应");
        if(prometheusPod==null)
            System.out.println("Pod: prometheus找不到，没有指标可以与其对应");
        //对每个指标确定其job是什么
        for(String metricName: metricsName){
//            if(!(metricName.equals("up")||metricName.equals("http_server_requests_seconds_sum")||metricName.equals("http_server_requests_seconds_count")||
//            metricName.equals("system_load_average_1m")||metricName.equals("node_load5")))
//                continue;
            try {
                //忽略告警指标、container_blkio_device_usage_total（这个指标没看懂）
                if(metricName.equals("ALERTS")||metricName.equals("container_blkio_device_usage_total"))
                    continue;
                String str = restTemplate.getForObject(promethsusQuery + "?query=" + metricName, String.class);
                if(str.contains("\"resultType\":\"vector\"")){
                    ExpressionQueriesVectorResponse expressionQueriesVectorResponse = gson.fromJson(str, ExpressionQueriesVectorResponse.class);
                    for(ResultVector resultVector: expressionQueriesVectorResponse.getData().getResult()){
                        if(resultVector.getMetric().containsKey("job")){
//                            System.out.println("Metric: "+ metric+", job: " + resultVector.getMetric().get("job"));
                            if(resultVector.getMetric().get("job").equals("kubernetes-apiservers")){
                                //全部和kube-apiserver的Pod关联
                                if(apiserverPod!=null){
                                    String relationId = metricName + "_" + apiserverPod.getName();
                                    if(!relations.contains(relationId)) {
                                        relations.add(relationId);
                                        Metric podMetric = new Metric(relationId,relationId,getCurrentTimestamp(),getCurrentTimestamp());
                                        metrics.put(podMetric.getId(), podMetric);
                                        PodAndMetric relation = new PodAndMetric(podMetric, apiserverPod, podMetric.getId() + "_MetricAndPod", "RUNTIME_INFO");
                                        podAndMetrics.add(relation);
                                    }
                                }
                            }else if(resultVector.getMetric().get("job").equals("kubernetes-nodes-kubelet")){
                                //只有和主机相连的情况
                                String relationId = metricName + "_" + resultVector.getMetric().get("instance");
                                if(!relations.contains(relationId)) {
                                    relations.add(relationId);
                                    Metric metric = new Metric(relationId, relationId, getCurrentTimestamp(), getCurrentTimestamp());
                                    metrics.put(metric.getId(), metric);
                                    MetricAndVirtualMachine metricAndVirtualMachine = new MetricAndVirtualMachine(metric, vms.get(resultVector.getMetric().get("instance")),
                                            relationId + "_MetricAndNode", "RUNTIME_INFO");
                                    metricAndVirtualMachines.add(metricAndVirtualMachine);
                                }
                            }else if(resultVector.getMetric().get("job").equals("kubernetes-nodes-cadvisor")){
                                //可能和主机相连也可能和容器连接
                                if(!resultVector.getMetric().containsKey("pod")||resultVector.getMetric().get("pod").equals("")){
                                    String relationId = metricName + "_" + resultVector.getMetric().get("instance");
                                    if(!relations.contains(relationId)) {
                                        relations.add(relationId);
                                        Metric metric = new Metric(relationId, relationId, getCurrentTimestamp(), getCurrentTimestamp());
                                        metrics.put(metric.getId(), metric);
                                        MetricAndVirtualMachine metricAndVirtualMachine = new MetricAndVirtualMachine(metric, vms.get(resultVector.getMetric().get("instance")),
                                                relationId + "_MetricAndNode", "RUNTIME_INFO");
                                        metricAndVirtualMachines.add(metricAndVirtualMachine);
                                    }
                                }else{
                                    if(resultVector.getMetric().get("name").equals("")){
                                        //和pod连
                                        String relationId = metricName + "_" + resultVector.getMetric().get("pod");
                                        if(!relations.contains(relationId)) {
                                            relations.add(relationId);
                                            Metric podMetric = new Metric(relationId, relationId, getCurrentTimestamp(), getCurrentTimestamp());
                                            metrics.put(podMetric.getId(), podMetric);
                                            PodAndMetric relation = new PodAndMetric(podMetric, pods.get(resultVector.getMetric().get("pod")), podMetric.getId() + "_MetricAndPod", "RUNTIME_INFO");
                                            podAndMetrics.add(relation);
                                        }
                                    }else{
                                        //和容器连
                                        String relationId = metricName + "_" + resultVector.getMetric().get("name");
                                        if(!relations.contains(relationId)){
                                            relations.add(relationId);
                                            if(containers.get(resultVector.getMetric().get("name"))==null){
                                                System.out.println("未查到容器" + resultVector.getMetric().get("name"));
                                                continue;
                                            }
                                            Metric metric = new Metric(relationId, relationId, getCurrentTimestamp(), getCurrentTimestamp());
                                            metrics.put(metric.getId(), metric);
                                            MetricAndContainer metricAndContainer = new MetricAndContainer(metric, containers.get(resultVector.getMetric().get("name")), metric.getName() + "_MetricAndContainer", "RUNTIME_INFO");
                                            metricAndContainers.add(metricAndContainer);
                                        }
                                    }
                                }
                            }else if(resultVector.getMetric().get("job").equals("kubernetes-service-endpoints")){
                                //和Pod连，instance为pod IP
                                String instance = resultVector.getMetric().get("instance");
                                String IP = instance.substring(0, instance.indexOf(":"));
                                if(IPpods.containsKey(IP)){
                                    //指标与该pod相连
                                    String relationId = metricName + "_" + IPpods.get(IP).getId();
                                    if(!relations.contains(relationId)) {
                                        relations.add(relationId);
                                        Metric podMetric = new Metric(relationId, relationId, getCurrentTimestamp(), getCurrentTimestamp());
                                        metrics.put(podMetric.getId(), podMetric);
                                        PodAndMetric relation = new PodAndMetric(podMetric, IPpods.get(IP), podMetric.getId() + "_MetricAndPod", "RUNTIME_INFO");
                                        podAndMetrics.add(relation);
                                    }
                                }else{
                                    System.out.println("Job kubernetes-service-endpoints's instance " + IP + " doesn't exist.");
                                }
                            }else if(resultVector.getMetric().get("job").equals("prometheus")){
                                //只和prometheus的Pod相连
                                if(prometheusPod!=null) {
                                    String relationId = metricName + "_" + prometheusPod.getId();
                                    if (!relations.contains(relationId)) {
                                        relations.add(relationId);
                                        Metric podMetric = new Metric(relationId, relationId, getCurrentTimestamp(), getCurrentTimestamp());
                                        metrics.put(podMetric.getId(), podMetric);
                                        PodAndMetric relation = new PodAndMetric(podMetric, prometheusPod, podMetric.getId() + "_MetricAndPod", "RUNTIME_INFO");
                                        podAndMetrics.add(relation);
                                    }
                                }
                            }else if(resultVector.getMetric().get("job").equals("kubernetes-nodes")){
                                String instance = resultVector.getMetric().get("instance");
                                String IP = instance.substring(0, instance.indexOf(":"));
                                //与主机连接
                                String relationId = metricName + "_" + IPToVM.get(IP).getId();
                                if(!relations.contains(relationId)) {
                                    relations.add(relationId);
                                    Metric metric = new Metric(relationId, relationId, getCurrentTimestamp(), getCurrentTimestamp());
                                    metrics.put(metric.getId(), metric);
                                    MetricAndVirtualMachine metricAndVirtualMachine = new MetricAndVirtualMachine(metric, IPToVM.get(IP),
                                            relationId + "_MetricAndNode", "RUNTIME_INFO");
                                    metricAndVirtualMachines.add(metricAndVirtualMachine);
                                }
                            }else if(resultVector.getMetric().get("job").equals("kubernetes-pods")){
                                //与pod相连
                                String podName = resultVector.getMetric().get("kubernetes_pod_name");
                                String relationId = metricName + "_" + podName;
                                if(!relations.contains(relationId)) {
                                    relations.add(relationId);
                                    Metric podMetric = new Metric(relationId, relationId, getCurrentTimestamp(), getCurrentTimestamp());
                                    metrics.put(podMetric.getId(), podMetric);
                                    PodAndMetric relation = new PodAndMetric(podMetric, pods.get(podName), podMetric.getId() + "_MetricAndPod", "RUNTIME_INFO");
                                    podAndMetrics.add(relation);
                                }
                            }
                        }else{
                            System.out.println("Metric: "+ metricName + "doesn't belong to any job.");
                        }
                    }
                }else{
                    System.out.println("[错误]Promethesus数据不合规 无法解析");
                }
            }catch (Exception e){
                System.out.println("[错误]未查到此Metric Metric名称:" + metricName);
            }
        }
    }

    public void uploadPodMetrics(){
        ArrayList<PodAndMetric> list = constructPodMetrics();
        System.out.println("上传Pod Metrics 数量:" + list.size());
        restTemplate.postForObject(
                neo4jDaoIP + "/podAndMetrics", list, list.getClass());

        ArrayList<String> podMetricIds = new ArrayList<>();
        for(PodAndMetric pm : list) {
            podMetricIds.add(pm.getPodMetric().getId());
        }
//        restTemplate.postForObject(graphAppIp + "/abnormality/podList",podMetricIds, String.class);

        System.out.println("上传Pod Metrics完毕");
    }

    private ArrayList<PodAndMetric> constructPodMetrics(){
        ArrayList<PodAndMetric> relations = new ArrayList<>();
        for(Pod pod : pods.values()){
            for(String podMetricName : podMetricNames) {
                try{
                    String queryStr = podMetricName +
                            "{" +
                            "pod=" + "\"" + pod.getName() + "\"}";
                    HashMap<String, String> getParameters = new HashMap<>();
                    getParameters.put("query", queryStr);
                    String str = restTemplate.getForObject(promethsusQuery + "?query={query}",String.class, getParameters);
                    if(str.contains("\"resultType\":\"vector\"")){
                        ExpressionQueriesVectorResponse res = gson.fromJson(str,ExpressionQueriesVectorResponse.class);

                        Metric podMetric = new Metric();
                        podMetric.setTime((long) Double.parseDouble(res.getData().getResult().get(0).getValue().get(0)));
                        podMetric.setValue(Double.parseDouble(res.getData().getResult().get(0).getValue().get(1)));
                        podMetric.setHistoryTimestamps(new ArrayList<>());
                        podMetric.setHistoryValues(new ArrayList<>());
                        //metric的ID为container的名字与metric的名字
                        podMetric.setId(pod.getName() + "_" + podMetricName);
                        podMetric.setName(pod.getName()  + "_" + podMetricName);
                        podMetric.setCreationTimestamp(getCurrentTimestamp());
                        podMetric.setLatestUpdateTimestamp(getCurrentTimestamp());


                        PodAndMetric relation = new PodAndMetric();
                        relation.setPod(pod);
                        relation.setPodMetric(podMetric);
                        relation.setId(podMetric.getId() + "MetricAndPod" + pod.getId());
                        relation.setRelation("RUNTIME_INFO");

                        relations.add(relation);

                    }else{
                        System.out.println("[错误]Promethesus数据不合规 无法解析");
                    }
                }catch (Exception e){
                    System.out.println("[错误]未查到此Pod的Metric Pod名称:" + pod.getName() + " Metric名称:" + podMetricName);
                }

            }
        }
        return relations;
    }

    private ArrayList<VirtualMachineAndPod> postVmAndPodList(ArrayList<VirtualMachineAndPod> relations){
        String str = restTemplate.postForObject(neo4jDaoIP + "/virtualMachineAndPodRelations",relations,String.class);
        Type founderListType = new TypeToken<ArrayList<VirtualMachineAndPod>>(){}.getType();
        ArrayList<VirtualMachineAndPod> result = gson.fromJson(str, founderListType);
        System.out.println("postVmAndPodList传输完毕");
        for(VirtualMachineAndPod relation : result){
            vms.put(relation.getVirtualMachine().getName(), relation.getVirtualMachine());
            if(relation.getPod().getPodIP()!=null) {
                pods.put(relation.getPod().getName(), relation.getPod());
                IPpods.put(relation.getPod().getPodIP(), relation.getPod());
            }
        }
        return result;
    }

    private ArrayList<AppServiceAndPod> postSvcAndPodList(ArrayList<AppServiceAndPod> relations){
        String str = restTemplate.postForObject(neo4jDaoIP + "/appServiceAndPodRelations",relations,String.class);
        Type founderListType = new TypeToken<ArrayList<AppServiceAndPod>>(){}.getType();
        ArrayList<AppServiceAndPod> result = gson.fromJson(str, founderListType);
        System.out.println("postSvcAndPodList传输完毕");
        for(AppServiceAndPod relation : result){
            svcs.put(relation.getAppService().getName(), relation.getAppService());
            if(relation.getPod().getPodIP()!=null) {
                pods.put(relation.getPod().getName(), relation.getPod());
                IPpods.put(relation.getPod().getPodIP(), relation.getPod());
            }
        }
        return result;
    }

    private ArrayList<PodAndContainer> postPodAndContainerList(ArrayList<PodAndContainer> relations){
        String str = restTemplate.postForObject(neo4jDaoIP + "/podAndContainerRelations",relations,String.class);
        Type founderListType = new TypeToken<ArrayList<PodAndContainer>>(){}.getType();
        ArrayList<PodAndContainer> result = gson.fromJson(str, founderListType);

        System.out.println("postPodAndContainerList传输完毕");
        for(PodAndContainer relation : result){
            if(relation.getPod().getPodIP()!=null) {
                pods.put(relation.getPod().getName(), relation.getPod());
                IPpods.put(relation.getPod().getPodIP(), relation.getPod());
            }
            containers.put(relation.getContainer().getName(), relation.getContainer());
        }
        return result;
    }

    private ArrayList<MetricAndContainer> postMetricAndContainerList(ArrayList<MetricAndContainer> relations){
        String str = restTemplate.postForObject(neo4jDaoIP + "/metricAndContainerRelations",relations,String.class);
        Type founderListType = new TypeToken<ArrayList<MetricAndContainer>>(){}.getType();
        ArrayList<MetricAndContainer> result = gson.fromJson(str, founderListType);

        System.out.println("postMetricAndContainerList传输完毕");
        return result;
    }

    private ArrayList<MetricAndVirtualMachine> postMetricAndVirtualMachine(ArrayList<MetricAndVirtualMachine> relations){
        String str = restTemplate.postForObject(neo4jDaoIP + "/metricAndVirtualMachine", relations, String.class);
        Type founderListType = new TypeToken<ArrayList<MetricAndVirtualMachine>>(){}.getType();
        ArrayList<MetricAndVirtualMachine> result = gson.fromJson(str, founderListType);
        System.out.println("postMetricAndVirtualMachine传输完毕");
        return result;
    }

    private ArrayList<MetricAndAlertRule> postMetricAndAlertRule(ArrayList<MetricAndAlertRule> relations){
        String str = restTemplate.postForObject(neo4jDaoIP + "/metricAndAlertRule", relations, String.class);
        Type founderListType = new TypeToken<ArrayList<MetricAndAlertRule>>(){}.getType();
        ArrayList<MetricAndAlertRule> result = gson.fromJson(str, founderListType);
        System.out.println("postMetricAndAlertRule传输完毕");
        return result;
    }

    private ArrayList<PodAndMetric> postPodAndMetricList(ArrayList<PodAndMetric> relations){
        String str = restTemplate.postForObject(neo4jDaoIP + "/podAndMetrics", relations, String.class);
        Type founderListType = new TypeToken<ArrayList<PodAndMetric>>(){}.getType();
        ArrayList<PodAndMetric> result = gson.fromJson(str, founderListType);
        System.out.println("postPodAndMetricList传输完毕");
        return result;
    }

    //使用抽取到的apiContainer并用apicontainer抽取
    public ArrayList<MetricAndContainer> constructMetricAndContainer(ArrayList<ApiContainer> apiContainers){
        ArrayList<MetricAndContainer> relations = new ArrayList<>();
        for(ApiContainer apiContainer : apiContainers){
            Container container = converApiContainerToContainer(apiContainer);
            String containerName = container.getName();
            if(containerName.startsWith("/")){
                containerName = containerName.substring(1);
            }
            //根据containernNamec抽取各种Metric
            for(String containerMetricName : containerMetricsNameVector){
                MetricAndContainer relation;
                //抽取container_memory_usage_bytes
                relation = assembleMetricAndContainer(containerMetricName, containerName, container);
                if(relation!=null) {
                    relations.add(relation);
//                    System.out.println(relation.getMetric().getName());
                }
            }
        }
        return relations;
    }

    //Assembly Metrics
    private MetricAndContainer assembleMetricAndContainer(String metricName, String containerName, Container container){
        ExpressionQueriesVectorResponse res = getMetric(metricName, containerName);
        if(res.getData().getResult().size()==0)return null;
        Metric metric = getMetricFromExpressionQueriesVectorResponse(res, metricName, containerName);
        MetricAndContainer relation = new MetricAndContainer();
        relation.setContainer(container);
        relation.setMetric(metric);
        relation.setId(metric.getId() + "MetricAndContainer" + container.getId());
        relation.setRelation("RUNTIME_INFO");
        relation.getContainer().setLatestUpdateTimestamp(currTimestampString);
        relation.getMetric().setLatestUpdateTimestamp(currTimestampString);

        return relation;
    }

    private Metric getMetricFromExpressionQueriesVectorResponse(ExpressionQueriesVectorResponse res,
                                                                String metricName, String containerName){
        Metric metric = new Metric();
        metric.setTime((long)Double.parseDouble(res.getData().getResult().get(0).getValue().get(0)));
        metric.setValue(Double.parseDouble(res.getData().getResult().get(0).getValue().get(1)));
        //metric的ID为container的名字与metric的名字
        metric.setId(containerName + "_" + metricName);
        metric.setName(containerName + "_" + metricName);
        metric.setCreationTimestamp("" + new Date().getTime() / 1000);

        return metric;
    }


    //使用抽取到的apiNode和apiPod
    private ArrayList<VirtualMachineAndPod> constructVmPodRelation(ArrayList<ApiNode> apiNodes,
                                                                  ArrayList<ApiPod> apiPods){
        ArrayList<VirtualMachineAndPod> relations = new ArrayList<>();
        for(ApiNode apiNode : apiNodes){
            //当前这个node叫什么名字
            String nodeName = apiNode.getMetadata().getName();
            VirtualMachine vm = convertApiNodeToVm(apiNode);
            for(ApiPod apiPod : apiPods){
                //找到pod的nodename和之前node的名字一样的那些Pod
                if(apiPod.getSpec().getNodeName().equals(nodeName)){
                    Pod pod = convertApiPodToPod(apiPod);
                    VirtualMachineAndPod relation = new VirtualMachineAndPod();
                    relation.setPod(pod);
                    relation.setVirtualMachine(vm);
                    relation.setRelation("Deploy-On");
                    relation.setId(pod.getId() + "PodVm" + vm.getId());
                    relation.getVirtualMachine().setLatestUpdateTimestamp(currTimestampString);
                    relation.getPod().setLatestUpdateTimestamp(currTimestampString);
                    relations.add(relation);
                }

            }
        }
        return relations;
    }

    //使用抽取到的apiPod和apiService
    private ArrayList<AppServiceAndPod> constructAppServicePodRelation(ArrayList<ApiAppService> apiAppServices,
                                                                      ArrayList<ApiPod> apiPods){
        ArrayList<AppServiceAndPod> relations = new ArrayList<>();
        for(ApiAppService apiAppService : apiAppServices){

            HashMap<String,String> svcSelector = apiAppService.getSpec().getSelector();
            if(svcSelector == null){
                continue;
            }

            AppService appService = convertApiAppServiceToAppService(apiAppService);
            for(ApiPod apiPod : apiPods) {
                HashMap<String,String> podLabel = apiPod.getMetadata().getLabels();
                boolean podIsSvc = false;
                for(Map.Entry<String,String> entry: podLabel.entrySet()){
                    if(svcSelector.containsKey(entry.getKey()) && svcSelector.get(entry.getKey()).equals(entry.getValue())){
                        podIsSvc = true;
                        break;
                    }
                }
                if(podIsSvc){
                    Pod pod = convertApiPodToPod(apiPod);
                    AppServiceAndPod relation = new AppServiceAndPod();
                    relation.setAppService(appService);
                    relation.setPod(pod);
                    relation.setRelation("BELONGS-TO");
                    relation.setId(pod.getId() + "PodSvc" + appService.getId());
                    relation.getAppService().setLatestUpdateTimestamp(currTimestampString);
                    relation.getPod().setLatestUpdateTimestamp(currTimestampString);
                    relations.add(relation);
                }
            }
        }
        return relations;
    }

    private ArrayList<PodAndContainer> constructPodAndContainerRelation(ArrayList<ApiPod> apiPods,
                                                                        ArrayList<ApiContainer> apiContainers){
        ArrayList<PodAndContainer> relations = new ArrayList<>();
        for(ApiPod apiPod : apiPods){
            //当前这个node叫什么名字
            String podName = apiPod.getMetadata().getName();
            Pod pod = convertApiPodToPod(apiPod);
            for(ApiContainer apiContainer : apiContainers){
                //找到容器的pod-name和之前pod名字一样的哪些container
                if(podName.equals(apiContainer.getLabels().get("io.kubernetes.pod.name"))){
                    Container container = converApiContainerToContainer(apiContainer);
                    PodAndContainer relation = new PodAndContainer();
                    relation.setContainer(container);
                    relation.setPod(pod);
                    relation.setRelation("DEPLOY-IN");
                    relation.setId(container.getId() + "ContainerPod" + pod.getId());
                    relation.getContainer().setLatestUpdateTimestamp(currTimestampString);
                    relation.getPod().setLatestUpdateTimestamp(currTimestampString);
                    relations.add(relation);
                }
            }
        }

        return relations;
    }

    //转换ApiNode到Virtual Machine
    private VirtualMachine convertApiNodeToVm(ApiNode node){

        VirtualMachine vm = new VirtualMachine();
        //虚拟机的ID就是虚拟机的名字
        vm.setId(node.getMetadata().getName());
        vm.setAddress(node.getStatus().getAddresses().get(0).getAddress());
        vm.setArchitecture(node.getStatus().getNodeInfo().getArchitecture());
        vm.setContainerRuntimeVersion(node.getStatus().getNodeInfo().getContainerRuntimeVersion());
        vm.setCpu(node.getStatus().getCapacity().getCpu());
        vm.setKernelVersion(node.getStatus().getNodeInfo().getKernelVersion());
        vm.setMemory(node.getStatus().getCapacity().getMemory());
        vm.setOperatingSystem(node.getStatus().getNodeInfo().getOperatingSystem());
        vm.setOsImage(node.getStatus().getNodeInfo().getOsImage());
        vm.setSelflink(node.getMetadata().getSelfLink());
        vm.setName(node.getMetadata().getName());
        vm.setCreationTimestamp(convertTime(node.getMetadata().getCreationTimestamp()));

        return vm;
    }

    //转换ApiPod到Pod
    private Pod convertApiPodToPod(ApiPod apiPod){

        Pod pod = new Pod();
        //Pod的ID就是Pod的名字
        pod.setId(apiPod.getMetadata().getName());
        pod.setDnsPolicy(apiPod.getSpec().getDnsPolicy());
        pod.setNamespace(apiPod.getMetadata().getNamespace());
        pod.setPhase(apiPod.getStatus().getPhase());
        pod.setPodIP(apiPod.getStatus().getPodIP());
        pod.setQosClass(apiPod.getStatus().getQosClass());
        pod.setName(apiPod.getMetadata().getName());
        pod.setRestartPolicy(apiPod.getSpec().getRestartPolicy());
        pod.setSelflink(apiPod.getMetadata().getSelfLink());
        pod.setTerminationGracePeriodSeconds(apiPod.getSpec().getTerminationGracePeriodSeconds());
        pod.setCreationTimestamp(convertTime(apiPod.getMetadata().getCreationTimestamp()));

        return pod;
    }

    //转换ApiService到Service
    private AppService convertApiAppServiceToAppService(ApiAppService apiAppService){

        AppService appService = new AppService();
        //AppService的ID就是服务的名字
        appService.setId(apiAppService.getMetadata().getName());
        appService.setClusterIP(apiAppService.getSpec().getClusterIP());
        appService.setNamespace(apiAppService.getMetadata().getNamespace());
        appService.setName(apiAppService.getMetadata().getName());
        appService.setPort(apiAppService.getSpec().getPorts().get(0).getPort());
        appService.setSelflink(apiAppService.getMetadata().getSelfLink());
        appService.setType(apiAppService.getSpec().getType());
        appService.setCreationTimestamp(convertTime(apiAppService.getMetadata().getCreationTimestamp()));

        return appService;
    }

    private Container converApiContainerToContainer(ApiContainer apiContainer){

        Container container = new Container();
        container.setCommand(apiContainer.getCommand());
        container.setState(apiContainer.getState());
        container.setStatus(apiContainer.getStatus());
        container.setCreated(apiContainer.getCreated());
        container.setImage(apiContainer.getImage());
        //使用真正的ID而不是自定义ID
        container.setId(apiContainer.getId());
        if(apiContainer.getNames().get(0).startsWith("/")){
            container.setName(apiContainer.getNames().get(0).substring(1));
        }else{
            container.setName(apiContainer.getNames().get(0));
        }
        container.setCreationTimestamp(apiContainer.getCreated());
        return container;
    }

    //把提取出的文字时间格式转换成时间戳
    private String convertTime(String timeStr){
        String utcTime = "2018-01-31T14:32:19Z";
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        //设置时区UTC
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        //格式化，转当地时区时间
        try{
            Date after = df.parse(timeStr);
            return "" + after.getTime() / 1000;
        }catch (Exception e){
            e.printStackTrace();
            return "ConvertTimeFailure";
        }
        //Wed Jan 31 22:32:19 GMT+08:00 2018
    }

    //"sidecar~10.38.0.12~ts-order-service-76f9cd9879-j45ls.default~default.svc.cluster.local"
    private static String fetchPodName(String fullPodName){
        fullPodName = fullPodName.substring(fullPodName.indexOf("~") + 1);
        fullPodName = fullPodName.substring(fullPodName.indexOf("~") + 1);
        int podTo = fullPodName.indexOf("~");

        String podNameWithNamespace = fullPodName.substring(0, podTo);
        int podEnd = fullPodName.indexOf(".");

        return fullPodName.substring(0, podEnd);
    }
}
