package com.fittrack.app;

import com.fittrack.app.CompositeDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CompositeService {
    private final JdbcTemplate jdbc;
    public CompositeService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public CompositeResponse completeSession(WorkoutSessionCompleteRequest request, String userId) {
        UUID user = owner(userId);
        UUID session = UUID.randomUUID();
        var s = request.session();
        jdbc.update("INSERT INTO workout_sessions(id,user_id,title,workout_type,duration_minutes,perceived_effort,notes,completed,completed_at) VALUES (?,?::uuid,?,?,?,?,?,?,?)",
                session,user,s.title(),s.workoutType(),s.durationMinutes(),s.perceivedEffort(),s.notes(),s.completed(),s.completed()?java.sql.Timestamp.from(Instant.now()):null);
        int childCount=0;
        for (SessionExerciseData e : request.exercises()) {
            requireCatalog("exercises", e.exerciseId());
            UUID we=UUID.randomUUID();
            jdbc.update("INSERT INTO workout_exercises(id,workout_session_id,exercise_id,order_index,notes) VALUES (?,?,?,?,?)",we,session,e.exerciseId(),e.orderIndex(),e.notes());
            for (ExerciseSetData set : e.sets()) {
                jdbc.update("INSERT INTO exercise_sets(id,workout_exercise_id,set_number,reps,weight,rpe,completed) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),we,set.setNumber(),set.reps(),set.weight(),set.rpe(),set.completed());
                childCount++;
            }
        }
        return new CompositeResponse(session,"workout_session",childCount);
    }

    @Transactional
    public CompositeResponse completeTemplate(WorkoutTemplateCompleteRequest request, String userId) {
        UUID user=owner(userId); UUID template=UUID.randomUUID(); var t=request.template();
        jdbc.update("INSERT INTO workout_templates(id,user_id,name,description,workout_type,estimated_minutes,is_favorite) VALUES (?,?::uuid,?,?,?,?,?)",template,user,t.name(),t.description(),t.workoutType(),t.estimatedMinutes(),t.favorite());
        int childCount=0;
        for(TemplateExerciseData e:request.exercises()){requireCatalog("exercises",e.exerciseId());jdbc.update("INSERT INTO workout_template_exercises(id,template_id,exercise_id,order_index,target_sets,target_reps,target_weight) VALUES (?,?,?,?,?,?,?)",UUID.randomUUID(),template,e.exerciseId(),e.orderIndex(),e.targetSets(),e.targetReps(),e.targetWeight());childCount++;}
        return new CompositeResponse(template,"workout_template",childCount);
    }

    @Transactional
    public CompositeResponse completeMeal(MealCompleteRequest request, String userId) {
        UUID user=owner(userId); UUID meal=UUID.randomUUID(); var m=request.meal();
        BigDecimal[] totals={BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO};
        var rows=new ArrayList<Map<String,Object>>();
        for(MealItemData i:request.items()){var food=food(i.foodId());BigDecimal grams=i.grams();BigDecimal[] n=nutrition(food,grams);for(int x=0;x<5;x++)totals[x]=totals[x].add(n[x]);rows.add(row("id", UUID.randomUUID(), "food_id", i.foodId(), "food_name", food.get("name"), "quantity", i.quantity(), "grams", grams, "calories", n[0], "protein_g", n[1], "carbs_g", n[2], "fat_g", n[3], "fiber_g", n[4], "source", m.source()==null?"manual":m.source()));}
        jdbc.update("INSERT INTO meals(id,user_id,meal_date,meal_type,name,source,calories,protein_g,carbs_g,fat_g,fiber_g) VALUES (?,?::uuid,?,?,?,?,?,?,?,?,?)",meal,user,m.mealDate(),m.mealType(),m.name(),m.source()==null?"manual":m.source(),totals[0],totals[1],totals[2],totals[3],totals[4]);
        for(var r:rows)jdbc.update("INSERT INTO meal_items(id,meal_id,food_id,food_name,quantity,grams,calories,protein_g,carbs_g,fat_g,fiber_g,source) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",r.get("id"),meal,r.get("food_id"),r.get("food_name"),r.get("quantity"),r.get("grams"),r.get("calories"),r.get("protein_g"),r.get("carbs_g"),r.get("fat_g"),r.get("fiber_g"),r.get("source"));
        return new CompositeResponse(meal,"meal",rows.size());
    }

    private Map<String,Object> row(Object... values){Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)row.put(values[i].toString(),values[i+1]);return row;}
    private void requireCatalog(String table,UUID id){Integer n=jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE id=?",Integer.class,id);if(n==null||n==0)throw new NoSuchElementException("Catalog reference not found");}
    private Map<String,Object> food(UUID id){return jdbc.queryForList("SELECT id,name,serving_size,calories,protein_g,carbs_g,fat_g,fiber_g FROM foods WHERE id=?",id).stream().findFirst().orElseThrow(()->new NoSuchElementException("Food not found"));}
    private BigDecimal[] nutrition(Map<String,Object> f,BigDecimal grams){BigDecimal serving=num(f.get("serving_size"));return new BigDecimal[]{scale(grams.multiply(num(f.get("calories"))).divide(serving,6,java.math.RoundingMode.HALF_UP)),scale(grams.multiply(num(f.get("protein_g"))).divide(serving,6,java.math.RoundingMode.HALF_UP)),scale(grams.multiply(num(f.get("carbs_g"))).divide(serving,6,java.math.RoundingMode.HALF_UP)),scale(grams.multiply(num(f.get("fat_g"))).divide(serving,6,java.math.RoundingMode.HALF_UP)),scale(grams.multiply(num(f.get("fiber_g"))).divide(serving,6,java.math.RoundingMode.HALF_UP))};}
    private BigDecimal num(Object v){return v==null?BigDecimal.ZERO:new BigDecimal(v.toString());} private BigDecimal scale(BigDecimal v){return v.setScale(2,java.math.RoundingMode.HALF_UP);} private UUID owner(String s){try{return UUID.fromString(s);}catch(Exception e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);}}
}
