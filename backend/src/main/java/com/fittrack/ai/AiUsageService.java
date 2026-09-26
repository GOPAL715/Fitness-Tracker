package com.fittrack.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AiUsageService {
    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);
    private final JdbcTemplate jdbc;
    private final int requestsPerMinute, requestsPerDay, tokensPerDay;
    private final BigDecimal inputPrice, outputPrice;
    private final String visionModel, textModel;
    public AiUsageService(JdbcTemplate jdbc,
            @Value("${app.ai-limits.requests-per-minute:10}") int rpm,
            @Value("${app.ai-limits.requests-per-day:100}") int rpd,
            @Value("${app.ai-limits.tokens-per-day:0}") int tpd,
            @Value("${app.ai-limits.pricing.input-per-million:0}") BigDecimal input,
            @Value("${app.ai-limits.pricing.output-per-million:0}") BigDecimal output,
            @Value("${app.ai-vision-model:unknown}") String vision,
            @Value("${app.ai-text-model:unknown}") String text) {
        this.jdbc=jdbc; requestsPerMinute=rpm; requestsPerDay=rpd; tokensPerDay=tpd;
        inputPrice=input==null?BigDecimal.ZERO:input; outputPrice=output==null?BigDecimal.ZERO:output;
        visionModel=vision; textModel=text;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Attempt start(String principal, String feature) {
        UUID user=user(principal), requestId=requestId(); String kind=feature==null?"unknown":feature; Instant now=Instant.now();
        if(tokensPerDay>0 && dailyTokens(user)>=tokensPerDay) throw new QuotaExceededException("daily_ai_token_limit");
        reserve(user,kind,"minute",now.truncatedTo(ChronoUnit.MINUTES),requestsPerMinute);
        reserve(user,kind,"day",now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(),requestsPerDay);
        log.info("ai_request_start request_id={} user_id={} feature={}",requestId,user,kind);
        return new Attempt(user,kind,requestId,System.nanoTime());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void success(Attempt a, AiProvider.ProviderUsage usage, String model) {
        String p=usage==null||usage.provider()==null?"openai-compatible":usage.provider();
        String m=model!=null?model:usage==null||usage.model()==null?defaultModel(a.feature()):usage.model();
        Integer in=usage==null?null:usage.inputTokens(), out=usage==null?null:usage.outputTokens(), total=usage==null?null:usage.totalTokens();
        insert(a,p,m,in,out,total,true,null,cost(in,out)); if(total!=null) addTokens(a.userId(),a.feature(),total);
        log.info("ai_request_end request_id={} user_id={} feature={} provider={} model={} success=true duration_ms={}",a.requestId(),a.userId(),a.feature(),p,m,elapsed(a));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void failure(Attempt a, Throwable error, String model) {
        String category=error instanceof QuotaExceededException?"quota":error instanceof AiProviderException?((AiProviderException)error).category():"application";
        insert(a,"openai-compatible",model!=null?model:defaultModel(a.feature()),null,null,null,false,category,null);
        log.info("ai_request_end request_id={} user_id={} feature={} success=false error_category={} duration_ms={}",a.requestId(),a.userId(),a.feature(),category,elapsed(a));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void quotaRejected(String principal, String feature, String category) {
        UUID user=user(principal), requestId=requestId(); String f=feature==null?"unknown":feature;
        insert(new Attempt(user,f,requestId,System.nanoTime()),"openai-compatible",defaultModel(f),null,null,null,false,category==null?"quota_rejected":category,null);
        log.info("ai_request_rejected request_id={} user_id={} feature={} error_category={}",requestId,user,f,category);
    }
    private void reserve(UUID user,String feature,String type,Instant start,int limit) {
        if(limit<=0)return;
        try { Integer n=jdbc.queryForObject("INSERT INTO ai_quota_counters(user_id,feature,window_type,window_start,request_count) VALUES (?,?,?,?,1) ON CONFLICT(user_id,feature,window_type,window_start) DO UPDATE SET request_count=ai_quota_counters.request_count+1 WHERE ai_quota_counters.request_count < ? RETURNING request_count",Integer.class,user,feature,type,Timestamp.from(start),limit); if(n==null)throw new QuotaExceededException(type+"_ai_request_limit"); }
        catch(EmptyResultDataAccessException e){throw new QuotaExceededException(type+"_ai_request_limit");}
    }
    private long dailyTokens(UUID user){Long n=jdbc.queryForObject("SELECT COALESCE(sum(token_count),0) FROM ai_quota_counters WHERE user_id=? AND window_type='day'",Long.class,user);return n==null?0:n;}
    private void addTokens(UUID user,String feature,int tokens){jdbc.update("UPDATE ai_quota_counters SET token_count=COALESCE(token_count,0)+? WHERE user_id=? AND feature=? AND window_type='day' AND window_start=?",tokens,user,feature,Timestamp.from(java.time.LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)));}
    private void insert(Attempt a,String provider,String model,Integer in,Integer out,Integer total,boolean ok,String error,BigDecimal cost){jdbc.update("INSERT INTO ai_usage(id,user_id,feature,model,provider,request_id,input_tokens,output_tokens,total_tokens,success,estimated_cost,latency_ms,error_category,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,now())",UUID.randomUUID(),a.userId(),a.feature(),model,provider,a.requestId(),in,out,total,ok,cost,elapsed(a),error);}
    private BigDecimal cost(Integer in,Integer out){if((in==null&&out==null)||(inputPrice.signum()==0&&outputPrice.signum()==0))return null;BigDecimal r=BigDecimal.ZERO;if(in!=null)r=r.add(BigDecimal.valueOf(in).multiply(inputPrice));if(out!=null)r=r.add(BigDecimal.valueOf(out).multiply(outputPrice));return r.divide(BigDecimal.valueOf(1_000_000),8,RoundingMode.HALF_UP);}
    private String defaultModel(String f){return "food_scan".equals(f)?visionModel:textModel;}
    private UUID requestId(){String v=org.slf4j.MDC.get("request_id");try{return v==null?UUID.randomUUID():UUID.fromString(v);}catch(IllegalArgumentException e){return UUID.randomUUID();}}
    private UUID user(String p){try{return UUID.fromString(p);}catch(Exception e){throw new AiProviderException("unauthorized","authentication");}}
    private long elapsed(Attempt a){return Math.max(0,(System.nanoTime()-a.startedNanos())/1_000_000);}
    public record Attempt(UUID userId,String feature,UUID requestId,long startedNanos){}
    public static class QuotaExceededException extends RuntimeException{public QuotaExceededException(String m){super(m);}}
}