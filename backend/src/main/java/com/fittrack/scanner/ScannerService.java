package com.fittrack.scanner;

import com.fittrack.ai.AiProvider;
import com.fittrack.scanner.ScannerController.ConfirmRequest;
import com.fittrack.scanner.ScannerController.ItemCorrection;
import com.fittrack.storage.PrivateObjectStorage;
import com.fittrack.storage.PrivateObjectStorage.StoredObject;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.IntStream;

@Service
public class ScannerService {
    static final long MAX_BYTES = 8L * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final PrivateObjectStorage storage;
    private final AiProvider ai;

    public ScannerService(JdbcTemplate jdbc, PrivateObjectStorage storage, AiProvider ai) {
        this.jdbc = jdbc; this.storage = storage; this.ai = ai;
    }

    @Transactional
    public ScanView create(MultipartFile file, String principal) {
        UUID user = user(principal);
        if (file == null || file.isEmpty()) throw bad("A non-empty file is required");
        if (file.getSize() > MAX_BYTES) throw bad("Image must not exceed 8 MB");
        String declared = file.getContentType();
        if (!Set.of("image/jpeg", "image/png", "image/webp").contains(declared)) throw bad("Unsupported image media type");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (Exception e) { throw bad("Image could not be read"); }
        String detected = detect(bytes);
        if (detected == null || !detected.equals(declared)) throw bad("File content does not match its declared image type");
        StoredObject stored;
        try { stored = storage.put(user.toString(), new ByteArrayInputStream(bytes), detected); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Image could not be stored", e); }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO food_scans(id,user_id,status,model,image_path,error,created_at) VALUES (?,?,?,?,?,?,now())",
                id, user, "processing", null, stored.key(), null);
        } catch (RuntimeException e) {
            try { storage.delete(user.toString(), stored.key()); } catch (Exception ignored) { }
            throw e;
        }
        try {
            AiProvider.FoodAnalysis result = ai.analyzeFood(bytes, detected);
            for (AiProvider.FoodItem item : result.items()) insertItem(id, item);
            jdbc.update("UPDATE food_scans SET status='completed',model=?,error=NULL WHERE id=? AND user_id=?", result.model(), id, user);
        } catch (RuntimeException e) {
            jdbc.update("UPDATE food_scans SET status='failed',error=? WHERE id=? AND user_id=?", safeMessage(e), id, user);
        }
        return get(id, user.toString());
    }

    public List<ScanView> list(String principal) {
        UUID user = user(principal);
        return jdbc.query("SELECT * FROM food_scans WHERE user_id=? ORDER BY created_at DESC LIMIT 100", (rs, n) -> view(rs.getObject("id", UUID.class), user), user);
    }
    public ScanView get(UUID id, String principal) {
        UUID user = user(principal);
        return jdbc.query("SELECT * FROM food_scans WHERE id=? AND user_id=?", (rs, n) -> view(rs.getObject("id", UUID.class), user), id, user).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Food scan not found"));
    }

    @Transactional
    public ScanView correct(UUID id, String principal, List<ItemCorrection> corrections) {
        ScanView scan = get(id, principal);
        if (!"completed".equals(scan.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only completed scans can be corrected");
        for (ItemCorrection c : corrections) {
            String name = c.name() == null ? null : c.name().trim();
            FoodMatch m = c.foodId() == null ? (name == null ? null : match(name)) : byId(c.foodId());
            if (name == null && m == null) throw bad("A corrected name or foodId is required");
            if (name == null) name = jdbc.queryForObject("SELECT name FROM foods WHERE id=?", String.class, m.id());
            if (jdbc.update("UPDATE food_scan_items SET food_id=?,food_name=?,confirmed_grams=?,user_edited=true,calories=0,protein_g=0,carbs_g=0,fat_g=0,fiber_g=0,sugar_g=0,sodium_mg=0 WHERE id=? AND scan_id=?", m == null ? null : m.id(), name, c.grams(), c.itemId(), id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Food scan item not found");
            if (m != null) jdbc.update("UPDATE food_scan_items SET calories=?,protein_g=?,carbs_g=?,fat_g=?,fiber_g=?,sugar_g=?,sodium_mg=? WHERE id=?", nut(c.grams(),m.calories(),m.servingSize()), nut(c.grams(),m.protein(),m.servingSize()), nut(c.grams(),m.carbs(),m.servingSize()), nut(c.grams(),m.fat(),m.servingSize()), nut(c.grams(),m.fiber(),m.servingSize()), nut(c.grams(),m.sugar(),m.servingSize()), nut(c.grams(),m.sodium(),m.servingSize()), c.itemId());
        }
        return get(id, principal);
    }

    @Transactional
    public ScanView confirm(UUID id, String principal, ConfirmRequest request) {
        UUID user = user(principal);
        ScanView scan = get(id, principal);
        if (!"completed".equals(scan.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Scan is not ready for confirmation");
        if (scan.items().isEmpty()) throw bad("A scan must contain at least one food item");
        UUID meal = UUID.randomUUID();
        LocalDate date = request.mealDate() == null ? LocalDate.now() : request.mealDate();
        String mealType = request.mealType() == null ? "SNACK" : request.mealType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("BREAKFAST", "LUNCH", "DINNER", "SNACK").contains(mealType)) throw bad("Invalid meal type");
        jdbc.update("INSERT INTO meals(id,user_id,meal_date,meal_type,name,source,calories,protein_g,carbs_g,fat_g) VALUES (?,?,?,?,?,?,?,?,?,?)",
            meal, user, date, mealType, request.mealName() == null ? "Scanned meal" : request.mealName().trim(), "food_scan", scan.totalCalories(), scan.totalProteinG(), scan.totalCarbsG(), scan.totalFatG());
        for (ItemView item : scan.items()) jdbc.update("INSERT INTO meal_items(id,user_id,meal_id,food_id,food_name,quantity,grams,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg,source) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'food_scan')",
            UUID.randomUUID(), user, meal, item.foodId(), item.name(), item.grams(), item.grams(), item.calories(), item.proteinG(), item.carbsG(), item.fatG(), item.fiberG(), item.sugarG(), item.sodiumMg());
        int changed = jdbc.update("UPDATE food_scans SET status='confirmed',meal_id=? WHERE id=? AND user_id=? AND status='completed'", meal, id, user);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Scan was already confirmed");
        return get(id, principal);
    }

    @Transactional
    public void delete(UUID id, String principal) {
        UUID user = user(principal);
        String key;
        try { key = jdbc.queryForObject("SELECT image_path FROM food_scans WHERE id=? AND user_id=?", String.class, id, user); }
        catch (EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Food scan not found"); }
        jdbc.update("DELETE FROM food_scans WHERE id=? AND user_id=?", id, user);
        try { storage.delete(user.toString(), key); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored image could not be removed", e); }
    }

    static String detect(byte[] b) {
        if (b != null && b.length >= 3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255) return "image/jpeg";
        byte[] png={(byte)137,80,78,71,13,10,26,10};
        if (b != null && b.length >= 8 && IntStream.range(0,8).noneMatch(i->b[i]!=png[i])) return "image/png";
        if (b != null && b.length >= 12 && b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='E'&&b[10]=='B'&&b[11]=='P') return "image/webp";
        return null;
    }
    private void insertItem(UUID scanId, AiProvider.FoodItem item) {
        if (item == null || item.name() == null || item.name().isBlank() || item.name().trim().length() > 160 || !Double.isFinite(item.grams()) || item.grams() < 1 || item.grams() > 5000 || !Double.isFinite(item.confidence()) || item.confidence() < 0 || item.confidence() > 1) throw bad("Invalid food detection");
        FoodMatch m=match(item.name()); BigDecimal grams=BigDecimal.valueOf(item.grams());
        jdbc.update("INSERT INTO food_scan_items(id,scan_id,food_id,food_name,estimated_grams,confirmed_grams,confidence,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg,user_edited) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,false)",
            UUID.randomUUID(),scanId,m==null?null:m.id(),item.name().trim(),grams,grams,BigDecimal.valueOf(item.confidence()),
            m==null?BigDecimal.ZERO:nut(grams,m.calories(),m.servingSize()),m==null?BigDecimal.ZERO:nut(grams,m.protein(),m.servingSize()),m==null?BigDecimal.ZERO:nut(grams,m.carbs(),m.servingSize()),m==null?BigDecimal.ZERO:nut(grams,m.fat(),m.servingSize()),
            m==null?BigDecimal.ZERO:nut(grams,m.fiber(),m.servingSize()),m==null?BigDecimal.ZERO:nut(grams,m.sugar(),m.servingSize()),m==null?BigDecimal.ZERO:nut(grams,m.sodium(),m.servingSize()));
    }
    private FoodMatch byId(UUID id) {
        List<FoodMatch> found = jdbc.query("SELECT id,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg,serving_size FROM foods WHERE id=?", (rs,n) -> new FoodMatch(rs.getObject("id",UUID.class),n(rs,"calories"),n(rs,"protein_g"),n(rs,"carbs_g"),n(rs,"fat_g"),n(rs,"fiber_g"),n(rs,"sugar_g"),n(rs,"sodium_mg"),n(rs,"serving_size")), id);
        return found.isEmpty() ? null : found.get(0);
    }
    private FoodMatch match(String name) {
        List<FoodMatch> matches=jdbc.query("SELECT id,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg,serving_size FROM foods WHERE lower(trim(name))=lower(trim(?)) ORDER BY name LIMIT 1",(rs,n)->new FoodMatch(rs.getObject("id",UUID.class),n(rs,"calories"),n(rs,"protein_g"),n(rs,"carbs_g"),n(rs,"fat_g"),n(rs,"fiber_g"),n(rs,"sugar_g"),n(rs,"sodium_mg"),n(rs,"serving_size")),name);
        return matches.isEmpty()?null:matches.get(0);
    }
    private static BigDecimal n(java.sql.ResultSet r,String c)throws java.sql.SQLException{BigDecimal v=r.getBigDecimal(c);return v==null?BigDecimal.ZERO:v;}
    private static BigDecimal nut(BigDecimal grams,BigDecimal per,BigDecimal serving){return per.signum()==0||serving.signum()==0?BigDecimal.ZERO:scale(grams.multiply(per).divide(serving,6,RoundingMode.HALF_UP));}
    private static BigDecimal scale(BigDecimal v){return v.setScale(2,RoundingMode.HALF_UP);}
    private ScanView view(UUID id,UUID user){return jdbc.query("SELECT * FROM food_scans WHERE id=? AND user_id=?",(rs,n)->{
        List<ItemView> items=jdbc.query("SELECT * FROM food_scan_items WHERE scan_id=? ORDER BY id",(ir,row)->new ItemView(ir.getObject("id",UUID.class),ir.getObject("food_id",UUID.class),ir.getString("food_name"),ir.getBigDecimal("confirmed_grams"),ir.getBigDecimal("confidence"),ir.getBigDecimal("calories"),ir.getBigDecimal("protein_g"),ir.getBigDecimal("carbs_g"),ir.getBigDecimal("fat_g"),ir.getBigDecimal("fiber_g"),ir.getBigDecimal("sugar_g"),ir.getBigDecimal("sodium_mg"),ir.getBoolean("user_edited")),id);
        BigDecimal[] totals=new BigDecimal[4]; for(ItemView i:items){totals[0]=add(totals[0],i.calories());totals[1]=add(totals[1],i.proteinG());totals[2]=add(totals[2],i.carbsG());totals[3]=add(totals[3],i.fatG());}
        TimestampHolder created=new TimestampHolder(rs.getTimestamp("created_at"));
        return new ScanView(id,rs.getString("status"),rs.getString("model"),rs.getObject("meal_id",UUID.class),rs.getString("error"),created.value,items,scale(nz(totals[0])),scale(nz(totals[1])),scale(nz(totals[2])),scale(nz(totals[3])));
    },id,user).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Food scan not found"));}
    private static BigDecimal add(BigDecimal a,BigDecimal b){return nz(a).add(nz(b));} private static BigDecimal nz(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private static UUID user(String p){if(p==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);try{return UUID.fromString(p);}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);}}
    private static ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);} private static String safeMessage(Exception e){String m=e.getMessage();return m==null||m.isBlank()?"Food analysis failed":m;}
    private record FoodMatch(UUID id,BigDecimal calories,BigDecimal protein,BigDecimal carbs,BigDecimal fat,BigDecimal fiber,BigDecimal sugar,BigDecimal sodium,BigDecimal servingSize){}
    private static class TimestampHolder{final Instant value;TimestampHolder(java.sql.Timestamp t){value=t.toInstant();}}
    public record ItemView(UUID id,UUID foodId,String name,BigDecimal grams,BigDecimal confidence,BigDecimal calories,BigDecimal proteinG,BigDecimal carbsG,BigDecimal fatG,BigDecimal fiberG,BigDecimal sugarG,BigDecimal sodiumMg,boolean userEdited){}
    public record ScanView(UUID id,String status,String model,UUID mealId,String error,Instant createdAt,List<ItemView> items,BigDecimal totalCalories,BigDecimal totalProteinG,BigDecimal totalCarbsG,BigDecimal totalFatG){}
}