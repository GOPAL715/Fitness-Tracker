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
        // started_at is server-generated and never client-supplied: it is the real instant the
        // session was recorded. session_date, supplied by the client, is the user's own calendar day
        // and is the field the calendar groups by, because started_at is a UTC instant and would file
        // an early-morning session under the previous day.
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        // A session records a day that has already happened, so a clearly future calendar date is a
        // client mistake. One day of tolerance is allowed, matching habit logs: the furthest any real
        // calendar is from UTC is +14:00, so a caller's local today can be a day ahead of the server's.
        if (s.sessionDate().isAfter(java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1))) {
            throw new IllegalArgumentException("session_date must not be in the future");
        }
        jdbc.update("INSERT INTO workout_sessions(id,user_id,title,workout_type,started_at,session_date,duration_minutes,perceived_effort,notes,completed,completed_at) VALUES (?,?::uuid,?,?,?,?,?,?,?,?,?)",
                session,user,s.title(),s.workoutType(),now,s.sessionDate(),s.durationMinutes(),s.perceivedEffort(),s.notes(),s.completed(),s.completed()?now:null);
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

    /**
     * Replaces an existing template and all of its exercise rows atomically.
     *
     * <p>The previous approach updated the parent, deleted the children and re-inserted them as three
     * separate requests, so a failure between them left the template with no exercises. Doing it in
     * one transaction means a rejected exercise, a bad id or a vanished template rolls the whole
     * change back and the stored template is left exactly as it was.
     *
     * <p>Ownership is resolved from the authenticated user before anything is written, so another
     * account's template is not even confirmed to exist.
     */
    @Transactional
    public CompositeResponse updateTemplate(String value, WorkoutTemplateUpdateRequest request, String userId) {
        UUID user = owner(userId);
        java.util.UUID template = id(value);
        var t = request.template();
        // Throws when the template is missing or belongs to somebody else, before any write happens.
        if (jdbc.queryForObject("select count(*) from workout_templates where id=? and user_id=?::uuid",
                Integer.class, template, user) == 0) {
            throw new NoSuchElementException("Resource not found");
        }
        jdbc.update("UPDATE workout_templates SET name=?,description=?,workout_type=?,estimated_minutes=?,is_favorite=? WHERE id=?",
                t.name(), t.description(), t.workoutType(), t.estimatedMinutes(), t.favorite(), template);
        jdbc.update("DELETE FROM workout_template_exercises WHERE template_id=?", template);
        for (TemplateExerciseData e : request.exercises()) {
            requireCatalog("exercises", e.exerciseId());
            jdbc.update("INSERT INTO workout_template_exercises(id,template_id,exercise_id,order_index,target_sets,target_reps,target_weight) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID(), template, e.exerciseId(), e.orderIndex(), e.targetSets(), e.targetReps(), e.targetWeight());
        }
        return new CompositeResponse(template, "workout_template", request.exercises().size());
    }

    /** Parses a path id, reporting a malformed value as a client error rather than a server fault. */
    private java.util.UUID id(String value) {
        try { return java.util.UUID.fromString(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid template id"); }
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
