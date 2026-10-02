package com.example.scms;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.*;

/**
 * One Lambda that routes all API calls.
 *
 *   GET    /courses               any signed-in user
 *   POST   /courses               admins only
 *   DELETE /courses/{courseId}    admins only
 *   GET    /enrollments           my enrolled courses
 *   POST   /enrollments/{id}      enroll me
 *   DELETE /enrollments/{id}      drop the course
 */
public class Handler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final DynamoDbClient DDB = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.create()).build();
    private static final String COURSES = System.getenv("COURSES_TABLE");
    private static final String ENROLLMENTS = System.getenv("ENROLLMENTS_TABLE");
    private static final Gson GSON = new Gson();

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context ctx) {
        try {
            String method = event.getRequestContext().getHttp().getMethod();
            String[] parts = event.getRawPath().replaceAll("^/|/$", "").split("/");
            String resource = parts[0];
            String id = parts.length > 1 ? parts[1] : null;

            // Claims come from the verified Cognito token (API Gateway already checked the signature)
            Map<String, String> claims = event.getRequestContext().getAuthorizer().getJwt().getClaims();
            String userId = claims.get("sub");
            String email = claims.getOrDefault("email", "");
            boolean isAdmin = claims.getOrDefault("cognito:groups", "").contains("admins");

            if (resource.equals("courses")) {
                if (method.equals("GET") && id == null) return ok(listCourses());
                if (method.equals("POST") && id == null) {
                    if (!isAdmin) return error(403, "Only admins can add courses");
                    return createCourse(event.getBody());
                }
                if (method.equals("DELETE") && id != null) {
                    if (!isAdmin) return error(403, "Only admins can delete courses");
                    return deleteCourse(id);
                }
            } else if (resource.equals("enrollments")) {
                if (method.equals("GET") && id == null) return ok(myCourses(userId));
                if (method.equals("POST") && id != null) return enroll(userId, email, id);
                if (method.equals("DELETE") && id != null) return drop(userId, id);
            }
            return error(404, "Route not found");
        } catch (Exception ex) {
            ctx.getLogger().log("ERROR: " + ex);
            return error(500, "Something went wrong on the server");
        }
    }

    // ---------- Courses ----------
    private List<Map<String, String>> listCourses() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, AttributeValue> item : DDB.scan(ScanRequest.builder().tableName(COURSES).build()).items()) {
            out.add(toCourse(item));
        }
        out.sort(Comparator.comparing(c -> c.getOrDefault("title", "").toLowerCase()));
        return out;
    }

    private APIGatewayV2HTTPResponse createCourse(String body) {
        Map<String, String> in = GSON.fromJson(body, new TypeToken<Map<String, String>>() {}.getType());
        if (in == null || blank(in.get("title"))) return error(400, "Course title is required");
        String courseId = UUID.randomUUID().toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("courseId", s(courseId));
        item.put("title", s(in.get("title").trim()));
        item.put("instructor", s(in.getOrDefault("instructor", "").trim()));
        item.put("description", s(in.getOrDefault("description", "").trim()));
        DDB.putItem(PutItemRequest.builder().tableName(COURSES).item(item).build());
        return response(201, toCourse(item));
    }

    private APIGatewayV2HTTPResponse deleteCourse(String courseId) {
        DDB.deleteItem(DeleteItemRequest.builder().tableName(COURSES).key(Map.of("courseId", s(courseId))).build());
        return ok(Map.of("deleted", courseId));
    }

    // ---------- Enrollments ----------
    private List<Map<String, String>> myCourses(String userId) {
        QueryResponse q = DDB.query(QueryRequest.builder().tableName(ENROLLMENTS)
                .keyConditionExpression("studentId = :s")
                .expressionAttributeValues(Map.of(":s", s(userId))).build());
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, AttributeValue> e : q.items()) {
            GetItemResponse c = DDB.getItem(GetItemRequest.builder().tableName(COURSES)
                    .key(Map.of("courseId", e.get("courseId"))).build());
            if (c.hasItem() && !c.item().isEmpty()) out.add(toCourse(c.item()));
        }
        return out;
    }

    private APIGatewayV2HTTPResponse enroll(String userId, String email, String courseId) {
        GetItemResponse c = DDB.getItem(GetItemRequest.builder().tableName(COURSES)
                .key(Map.of("courseId", s(courseId))).build());
        if (!c.hasItem() || c.item().isEmpty()) return error(404, "Course not found");
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("studentId", s(userId));
        item.put("courseId", s(courseId));
        item.put("email", s(email));
        item.put("enrolledAt", s(Instant.now().toString()));
        try {
            DDB.putItem(PutItemRequest.builder().tableName(ENROLLMENTS).item(item)
                    .conditionExpression("attribute_not_exists(courseId)").build());
        } catch (ConditionalCheckFailedException dup) {
            return error(409, "You are already enrolled in this course");
        }
        return response(201, Map.of("enrolled", courseId));
    }

    private APIGatewayV2HTTPResponse drop(String userId, String courseId) {
        DDB.deleteItem(DeleteItemRequest.builder().tableName(ENROLLMENTS)
                .key(Map.of("studentId", s(userId), "courseId", s(courseId))).build());
        return ok(Map.of("dropped", courseId));
    }

    // ---------- helpers ----------
    private static Map<String, String> toCourse(Map<String, AttributeValue> item) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : List.of("courseId", "title", "instructor", "description")) {
            m.put(k, item.containsKey(k) ? item.get(k).s() : "");
        }
        return m;
    }
    private static AttributeValue s(String v) { return AttributeValue.builder().s(v).build(); }
    private static boolean blank(String v) { return v == null || v.trim().isEmpty(); }
    private static APIGatewayV2HTTPResponse ok(Object body) { return response(200, body); }
    private static APIGatewayV2HTTPResponse error(int code, String msg) { return response(code, Map.of("error", msg)); }
    private static APIGatewayV2HTTPResponse response(int code, Object body) {
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(code)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(GSON.toJson(body)).build();
    }
}
