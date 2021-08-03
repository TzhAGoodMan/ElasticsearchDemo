package com.example.elasticsearchdemo.service.impl;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.example.elasticsearchdemo.common.enums.ENEsIndex;
import com.example.elasticsearchdemo.common.utils.EsRestApiUtil;
import com.example.elasticsearchdemo.dao.SeBdDao;
import com.example.elasticsearchdemo.esRepository.PersonRepository;
import com.example.elasticsearchdemo.pojo.Person;
import com.example.elasticsearchdemo.pojo.SeBd;
import com.example.elasticsearchdemo.service.EsTestService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.index.reindex.UpdateByQueryRequest;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 使用ES 的rest API 的测试service
 *
 * @author tianzh
 * @date 2021/07/29
 */
@Service
public class EsTestServiceImpl implements EsTestService {
    @Autowired
    RestHighLevelClient highLevelClient;
    @Autowired
    private SeBdDao seBdDao;
    @Autowired
    PersonRepository personRepository;

    @Override
    public void testEsRestDeleteApi() {
        try {
            DeleteByQueryRequest request = new DeleteByQueryRequest(ENEsIndex.INDEX_PERSON_ES_TEST.getValue());
            request.setQuery(QueryBuilders.matchQuery("name", "田")
                    // 启动模糊查询
                    .fuzziness(Fuzziness.AUTO)
                    // 在匹配查询上设置前缀长度选项
                    .prefixLength(3)
                    // 设置最大扩展选项以控制查询的模糊过程
                    .maxExpansions(10));

//            highLevelClient.delete() 此api需要知道文档的id
            // 根据条件查询出来  再delete掉
            BulkByScrollResponse bulkByScrollResponse = highLevelClient.deleteByQuery(request, RequestOptions.DEFAULT);
//            highLevelClient.deleteByQueryAsync(); //异步删除方法
            // TODO 对响应体的一些处理
            System.out.println("已删除：" + bulkByScrollResponse.getDeleted() + "条");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void testEsRestUpdateApi() {
        try {

            UpdateByQueryRequest updateByQueryRequest = new UpdateByQueryRequest(ENEsIndex.INDEX_PERSON_ES_TEST.getValue());
            updateByQueryRequest.setQuery(QueryBuilders.matchQuery("name", "田")
                    // 启动模糊查询
                    .fuzziness(Fuzziness.AUTO)
                    // 在匹配查询上设置前缀长度选项
                    .prefixLength(3)
                    // 设置最大扩展选项以控制查询的模糊过程
                    .maxExpansions(10));
            // 使用脚本更新
            updateByQueryRequest.setScript(
                    new Script(
                            ScriptType.INLINE, "painless",
                            "if (ctx._source.name == '田梓豪') {ctx._source.age= 18}",
                            Collections.emptyMap()));

            highLevelClient.updateByQuery(updateByQueryRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void testEsRestSelectApi() {
        try {

            SearchResponse searchResponse = highLevelClient.search(
                    EsRestApiUtil.createFuzzinessSearchRequest(
                            "name", "田", ENEsIndex.INDEX_PERSON_ES_TEST.getValue()),
                    RequestOptions.DEFAULT);

            // 获取命中次数，查询结果有多少对象
            SearchHits hits = searchResponse.getHits();

            for (SearchHit searchHit : hits) {
                System.out.println();
                System.out.println(searchHit.getSourceAsString());
                System.out.println();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public void testEsMSearch() {
        try {
            MultiSearchRequest multiSearchRequest = new MultiSearchRequest();
            multiSearchRequest.add(EsRestApiUtil.createFuzzinessSearchRequest(
                    "name", "张三", ENEsIndex.INDEX_PERSON_ES_TEST.getValue()));


            multiSearchRequest.add(new SearchRequest(ENEsIndex.INDEX_PERSON_ES_TEST.getValue())
                    .source(new SearchSourceBuilder().query(QueryBuilders.termQuery("age", 20))));
            MultiSearchResponse msearchResponse = highLevelClient.msearch(multiSearchRequest, RequestOptions.DEFAULT);

            // TODO 这个方法其实还是分词查询
            msearchResponse.forEach(t -> {
                SearchResponse resp = t.getResponse();

                Arrays.stream(resp.getHits().getHits())
                        .forEach(i -> {
                            System.out.println(i.getSourceAsString());
                        });
                System.out.println(resp.getHits().getTotalHits());
            });

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public void testBoolQuery() {
        try {
            BoolQueryBuilder boolQueryBuilder = QueryBuilders
                    .boolQuery()
                    .must(QueryBuilders.matchQuery("name", "张三"))
                    .must(QueryBuilders.matchQuery("age", 20))
                    .must(QueryBuilders.matchQuery("gender", "男"));
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();

            // 置查询的起始索引位置和数量
            searchSourceBuilder.from(0);
            searchSourceBuilder.size(50);
            // 60秒超时返回
            searchSourceBuilder.timeout(new TimeValue(60, TimeUnit.SECONDS));
            searchSourceBuilder.query(boolQueryBuilder);
            // sort可能会报错，类型为Text格式，然后涉及到了聚合排序等功能。没有进行优化，也类似没有加索引。没有优化的字段es默认是禁止聚合/排序操作的。所以需要将要聚合的字段添加优化
//            searchSourceBuilder.sort("gender", SortOrder.ASC);
            SearchRequest searchRequest = new SearchRequest(ENEsIndex.INDEX_PERSON_ES_TEST.getValue())
                    .source(searchSourceBuilder);
            SearchResponse searchResponse = highLevelClient.search(searchRequest, RequestOptions.DEFAULT);
            // 获取命中次数，查询结果有多少对象
            SearchHits hits = searchResponse.getHits();

            for (SearchHit searchHit : hits) {
                System.out.println();
                System.out.println(searchHit.getSourceAsString());
                System.out.println();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void testEsRestCreateIndexApi() {
        try {
            CreateIndexRequest createIndexRequest = new CreateIndexRequest(ENEsIndex.INDEX_PERSON_ES_TEST.getValue());
            // 设置索引的settings
            createIndexRequest.settings(Settings.builder()
                    // 分片数
                    .put("index.number_of_shards", 3)
                    // 副本数
                    .put("index.number_of_replicas", 3)
                    // 默认分词器
                    .put("analysis.analyzer.default.tokenizer", "ik_max_word"));

            CreateIndexResponse createIndexResponse =
                    highLevelClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
            boolean acknowledged = createIndexResponse.isAcknowledged();
            boolean shardsAcknowledged = createIndexResponse.isShardsAcknowledged();
            System.out.println("acknowledged = " + acknowledged);
            System.out.println("shardsAcknowledged = " + shardsAcknowledged);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void insertSebdToEs() {
        try {
            int oneSelect = 10000;
            int num = 1;
            PageInfo<SeBd> seBdPageInfo = this.pageSelect(num, oneSelect);
            if (seBdPageInfo.getList().size()> 0) {
                BulkRequest bulkRequest = new BulkRequest();
                seBdPageInfo.getList().forEach(x ->
                        bulkRequest.add(EsRestApiUtil.createIndexRequest(x, ENEsIndex.INDEX_SE_BD.getValue())));
               // 它还没写完  下一次操作就来了
                highLevelClient.bulk(bulkRequest, RequestOptions.DEFAULT);
            }

            int pages = seBdPageInfo.getPages();
            for (int i = 2 ; i <= pages; i++) {
                seBdPageInfo = this.pageSelect(i, oneSelect);
                BulkRequest bulkRequest = new BulkRequest();
                seBdPageInfo.getList().forEach(x ->
                        bulkRequest.add(EsRestApiUtil.createIndexRequest(x, ENEsIndex.INDEX_SE_BD.getValue())));
                highLevelClient.bulk(bulkRequest, RequestOptions.DEFAULT);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private PageInfo<SeBd> pageSelect(int num, int size) {
        PageHelper.startPage(num, size);
        return new PageInfo<>(seBdDao.selectAll());
    }
    @Override
    public void testEsRestIndexApi() {
        try {
//            highLevelClient.indices().create()
            Person person = new Person();
            person.setName("张三");
            person.setAge(20);
            person.setGender("女");
            highLevelClient.index(EsRestApiUtil.createIndexRequest(person, ENEsIndex.INDEX_PERSON_ES_TEST.getValue()),
                    RequestOptions.DEFAULT);


            SeBd seBd = new SeBd();
            seBd.setUrid(0);
            seBd.setEntNum("");
            seBd.setBatNo("");
            seBd.setBdNo("");
            seBd.setEntChannelCode("");
            seBd.setEntAccNum("");
            seBd.setCardType("");
            seBd.setCustBankCode("");
            seBd.setCustAccNum("");
            seBd.setCustAccName("");
            seBd.setCustAreaCode("");
            seBd.setCustBankLocationCode("");
            seBd.setCustBankLocationName("");
            seBd.setPrivateFlag("");
            seBd.setAmount("");
            seBd.setCurrency("");
            seBd.setProtocolCode("");
            seBd.setProtocolUserCode("");
            seBd.setCertType("");
            seBd.setCertNum("");
            seBd.setPhone("");
            seBd.setReconciliationCode("");
            seBd.setPurpose("");
            seBd.setMemo("");
            seBd.setBatchToSingleFlag("");
            seBd.setFlowNo("");
            seBd.setSingleTransVerifyCode("");
            seBd.setSingleQueryVerifyCode("");
            seBd.setSourceNote("");
            seBd.setOrgCode("");
            seBd.setSameBankFlag("");
            seBd.setUrgentFlag("");
            seBd.setSourceUrid("");
            seBd.setWarnRevoked("");
            seBd.setRepeatConditionValue("");
            seBd.setWarnDate("");
            seBd.setDetailMd5("");
            seBd.setBankExtend1("");
            seBd.setBankExtend2("");
            seBd.setBankExtend3("");
            seBd.setBankExtend4("");
            seBd.setBankExtend5("");
            seBd.setBankReturnState("");
            seBd.setBankReturnCode("");
            seBd.setBankReturnMsg("");
            seBd.setBankLocationMatchFlag(0);
            seBd.setBankLocationMatchName("");
            seBd.setMoneyWay("");
            seBd.setAccountingDate("");
            seBd.setFgUniqueId("");
            seBd.setMatchBankId("");
            seBd.setMatchAreaCode("");
            seBd.setBankDetailReconcile("");
            seBd.setReconcileFileReconcile("");
            seBd.setTotalAmountReconcile("");
            seBd.setFailBankDetailReconcile("");
            seBd.setFailAccountingDate("");
            seBd.setCycleDate(new Date());
            seBd.setFailCycleDate(new Date());
            seBd.setSupportCreditFlag("");
            seBd.setUnionPayCardType("");
            seBd.setAbsTract("");
            seBd.setComplianceState("");
            seBd.setComplianceInfo("");
            seBd.setOverLength("");
            seBd.setRowVersion(0);
            seBd.setDecodeAmount(0.0D);
            seBd.setAccountingFlag("");
            seBd.setReqDate(new Date());
            seBd.setBankReturnTime(new Date());
            seBd.setPolicyNum("");
            seBd.setCustAreaName("");
            seBd.setEncryptFlag("");
            seBd.setConvertedBdNo("");
            seBd.setRiskControlState("");
            seBd.setRiskControlHitType("");
            seBd.setInTime(new Date());
            seBd.setSendBankTime(new Date());
            seBd.setFailReconcileType("");
            seBd.setLastQueryTime(new Date());
            seBd.setLastModifyTime(new Date());
            seBd.setDayFlowNo("");
            seBd.setAccountingNo("");
            seBd.setRemainQueryCount(0);
            seBd.setProtocolNum("");
            seBd.setAgreementNo("");
            seBd.setSignBusType("");
            seBd.setPrdName("");
            seBd.setSignTrans("");
            seBd.setMatchedAreaCode("");
            seBd.setMatchedBankLocationCode("");
            seBd.setMatchedBankLocationName("");
            seBd.setMatchedCustBankCode("");
            seBd.setRefundFlag("");
            seBd.setIcbcSignRespMsg("");
            seBd.setFgResultOutLibrary("");
            seBd.setCurrentBankReturnResult("");
            seBd.setBelongAreaMatched("");
            seBd.setBelongAreaMatchRule("");
            seBd.setSourceBankReturnCode("");
            seBd.setSourceBankReturnMsg("");


            highLevelClient.index(EsRestApiUtil.createIndexRequest(seBd, ENEsIndex.INDEX_SE_BD.getValue()),
                    RequestOptions.DEFAULT);


//            GetRequest getRequest = new GetRequest(
//                    "person_es_test",
//                    "田梓豪3");
//
//            GetResponse result = highLevelClient.get(getRequest, RequestOptions.DEFAULT);
//            if (result.isExists()) {
//                System.out.println(result.getSourceAsMap().toString());
//            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
