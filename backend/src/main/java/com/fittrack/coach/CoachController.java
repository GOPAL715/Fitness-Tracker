package com.fittrack.coach;
import com.fittrack.ai.AiProvider;
import com.fittrack.ai.AiUsageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/v1/coach")
public class CoachController {
  private final JdbcTemplate jdbc; private final AiProvider ai; private final AiUsageService usage;
  public CoachController(JdbcTemplate jdbc,AiProvider ai,AiUsageService usage){this.jdbc=jdbc;this.ai=ai;this.usage=usage;}
  @PostMapping("/analyze") public Map<String,Object> analyze(@AuthenticationPrincipal String user){
    var facts=new LinkedHashMap<String,Object>();
    facts.put("dailyMetrics",jdbc.queryForList("SELECT metric_date,steps,sleep_hours,calories_burned,active_minutes FROM daily_metrics WHERE user_id=CAST(? AS uuid) ORDER BY metric_date DESC LIMIT 14",user));
    facts.put("bodyMetrics",jdbc.queryForList("SELECT metric_date,weight_lb,body_fat_pct FROM body_metrics WHERE user_id=CAST(? AS uuid) ORDER BY metric_date DESC LIMIT 14",user));
    facts.put("workouts",jdbc.queryForList("SELECT workout_date,duration_minutes,calories_burned,completed FROM workouts WHERE user_id=CAST(? AS uuid) ORDER BY workout_date DESC LIMIT 20",user));
    facts.put("meals",jdbc.queryForList("SELECT meal_date,calories,protein_g FROM meals WHERE user_id=CAST(? AS uuid) ORDER BY meal_date DESC LIMIT 20",user));
    AiUsageService.Attempt attempt;
    try { attempt=usage.start(user,"weekly_coach"); }
    catch (AiUsageService.QuotaExceededException e) { usage.quotaRejected(user,"weekly_coach","quota"); throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"AI request limit exceeded"); }
    try { AiProvider.CoachAnalysis result=ai.analyzeCoachWithMetadata(facts.toString()); if(result==null) result=new AiProvider.CoachAnalysis(null,ai.analyzeCoach(facts.toString()),null); usage.success(attempt,result.usage(),result.model()); return Map.of("analysis",result.text(),"facts",facts); }
    catch(RuntimeException e){usage.failure(attempt,e,null);throw e;}
  }
  @PostMapping("/weekly-review") public Map<String,String> weekly(@AuthenticationPrincipal String user){
    AiUsageService.Attempt attempt;
    try { attempt=usage.start(user,"weekly_coach_review"); }
    catch (AiUsageService.QuotaExceededException e) { usage.quotaRejected(user,"weekly_coach_review","quota"); throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"AI request limit exceeded"); }
    try { AiProvider.CoachAnalysis result=ai.analyzeCoachWithMetadata("weekly factual summary"); if(result==null) result=new AiProvider.CoachAnalysis(null,ai.analyzeCoach("weekly factual summary"),null); usage.success(attempt,result.usage(),result.model()); return Map.of("review",result.text()); }
    catch(RuntimeException e){usage.failure(attempt,e,null);throw e;}
  }
}