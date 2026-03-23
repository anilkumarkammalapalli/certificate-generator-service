package org.sunbird.cert.helper;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sunbird.BaseException;
import org.sunbird.HttpUtil;
import org.sunbird.JsonKeys;
import org.sunbird.PropertiesCache;
import org.sunbird.cache.util.RedisCacheUtil;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.cert.Models.AssessmentUserAttempt;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.response.Response;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class IssueCertificateContentHelper {
    private static final Logger logger = LoggerFactory.getLogger(IssueCertificateContentHelper.class);

    private static final CassandraOperation cassandraOperation = ServiceFactory.getInstance();
    private static final RedisCacheUtil contentCache = new RedisCacheUtil();
    private static ObjectMapper mapper = new ObjectMapper();
    private static PropertiesCache propertiesCache = PropertiesCache.getInstance();

    static {
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private static IssueCertificateContentHelper instance = null;

    public static synchronized IssueCertificateContentHelper getInstance() {
        if (instance == null) {
            instance = new IssueCertificateContentHelper();
        }
        return instance;
    }

    private IssueCertificateContentHelper() {}

    public Map<String, Object> generateCertificateMap(Map<String, Object> requestMap, Map<String, Object> template) {

        try {
            logger.info("issueCertificate i/p event =>" + requestMap);

            String courseId = (String) requestMap.get(JsonKeys.COURSE_ID);
            String userId = (String) requestMap.get(JsonKeys.USER_ID);
            String batchId = (String) requestMap.get(JsonKeys.BATCH_ID);

            Map<String, Object> criteria = validateTemplate(template, batchId);
            String certName = (String) template.getOrDefault(JsonKeys.NAME, "");
            logger.info("CertName: " + certName);

            Map<String, List<String>> additionalProps = mapper.readValue((String) template.getOrDefault(JsonKeys.ADDITIONAL_PROPS, "{}"), Map.class);

            Map<String, Object> enrolledUser = validateEnrolmentCriteria(requestMap, (Map<String, Object>) criteria.getOrDefault(JsonKeys.ENROLLMENT, new HashMap<>()), certName, additionalProps);
            logger.debug("enrolledUser: " + enrolledUser);

            Map<String, Object> assessedUser = validateAssessmentCriteria(requestMap, (Map<String, Object>) criteria.getOrDefault(JsonKeys.ASSESSMENT, new HashMap<>()), userId, additionalProps);
            logger.debug("assessedUser: " + assessedUser);

            Map<String, Object> userDetails = validateUser(userId, (Map<String, Object>) criteria.getOrDefault(JsonKeys.USERS, new HashMap<>()), additionalProps);
            logger.debug("userDetails: " + userDetails);

            if (!userDetails.isEmpty()) {
                return generateCertificateEvent(requestMap, template, userDetails, certName, enrolledUser);
            } else {
                logger.error(String.format("User :: %s did not match the criteria for batch :: %s and course :: %s", userId, batchId, courseId));
                return null;
            }
        } catch (Exception e) {
            logger.error("Issue while validating the user Enrollment.");
        }
        return null;
    }

    // Placeholder methods for dependent logic
    private static Map<String, Object> validateTemplate(Map<String, Object> template, String batchId) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> criteria = objectMapper.readValue((String) template.getOrDefault(JsonKeys.CRITERIA, "{}"), Map.class);

        if (StringUtils.isNotBlank((String) template.getOrDefault(JsonKeys.URL, "")) && !criteria.isEmpty() && !Collections.disjoint(criteria.keySet(), Arrays.asList(JsonKeys.ENROLLMENT, JsonKeys.ASSESSMENT, JsonKeys.USERS))) {
            return criteria;
        } else {
            throw new Exception("Invalid template for batch: " + batchId);
        }
    }

    private static Map<String, Object> validateEnrolmentCriteria(Map<String, Object> requestMap, Map<String, Object> enrollmentCriteria, String certName, Map<String, List<String>> additionalProps) throws BaseException {
        Boolean reIssue = false;
        if (MapUtils.isNotEmpty(enrollmentCriteria)) {
            Map<String, Object> primaryKey = new HashMap<>();
            String courseId = (String) requestMap.get(JsonKeys.COURSE_ID);
            String userId = (String) requestMap.get(JsonKeys.USER_ID);
            String batchId = (String) requestMap.get(JsonKeys.BATCH_ID);
            primaryKey.put(JsonKeys.USER_ID, userId);
            primaryKey.put(JsonKeys.COURSE_ID, courseId);
            primaryKey.put(JsonKeys.BATCH_ID, batchId);
            Response row = cassandraOperation.getRecordsByProperties(JsonKeys.COURSE_KEY_SPACE_NAME, JsonKeys.USER_ENROLMENTS_V2, primaryKey);
            if (row != null) {
                List<Map<String, Object>> mapList = (List<Map<String, Object>>) row.get(JsonKeys.RESPONSE);
                if (CollectionUtils.isNotEmpty(mapList)) {
                    Map<String, Object> map = mapList.get(0);

                    boolean active = (boolean) map.getOrDefault(JsonKeys.ACTIVE, false);
                    List<Map<String, String>> issuedCertificates = (List<Map<String, String>>) map.getOrDefault(JsonKeys.ISSUED_CERTIFICATES, new ArrayList<>());

                    boolean isCertIssued = !issuedCertificates.isEmpty() && issuedCertificates.stream().anyMatch(cert -> certName.equalsIgnoreCase(cert.getOrDefault(JsonKeys.NAME, "")));

                    int status = (int) map.getOrDefault(JsonKeys.STATUS, 0);
                    int criteriaStatus = (int) enrollmentCriteria.getOrDefault(JsonKeys.STATUS, 2);

                    String oldId = (isCertIssued && reIssue) ? issuedCertificates.stream().filter(cert -> certName.equalsIgnoreCase(cert.getOrDefault(JsonKeys.NAME, ""))).map(cert -> cert.getOrDefault(JsonKeys.IDENTIFIER, "")).findFirst().orElse("") : "";

                    String userIds = (active && (criteriaStatus == status) && (!isCertIssued || reIssue)) ? userId : "";

                    Date issuedOn = (Date) map.get(JsonKeys.COMPLETED_ON);

                    Optional<String> courseCompletionLanguage = issuedCertificates.stream()
                            .filter(cert -> cert.containsKey(JsonKeys.COURSE_COMPLETION_LANGUAGE))
                            .map(cert -> cert.get(JsonKeys.COURSE_COMPLETION_LANGUAGE))
                            .findFirst();
                    Map<String, Object> enrolledUserMap = new HashMap<>();
                    if (courseCompletionLanguage.isPresent()) {
                        logger.info("Course Completion Language: " + courseCompletionLanguage.get());
                        enrolledUserMap.put(JsonKeys.COURSE_COMPLETION_LANGUAGE, courseCompletionLanguage.get());
                    }
                    enrolledUserMap.put(JsonKeys.USER, userId);
                    enrolledUserMap.put(JsonKeys.OLD_ID, oldId);
                    enrolledUserMap.put(JsonKeys.ISSUED_ON, issuedOn);
                    if (!additionalProps.isEmpty()) {
                        enrolledUserMap.put(JsonKeys.ENROLLMENT, additionalProps);
                    }
                    return enrolledUserMap;
                }
            }
        }
        return new HashMap<>();
    }

    private static Map<String, Object> validateAssessmentCriteria(Map<String, Object> requestMap, Map<String, Object> assessmentCriteria, String enrolledUser, Map<String, List<String>> additionalProps) throws BaseException {
        if (!assessmentCriteria.isEmpty() && !enrolledUser.isEmpty()) {
            Map<String, List<AssessmentUserAttempt>> filteredUserAssessments = getMaxScore(requestMap);

            Map<String, Double> scoreMap = filteredUserAssessments.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> (e.getValue().get(0).getScore() * 100.0) / e.getValue().get(0).getTotalScore()));

            double score = scoreMap.isEmpty() ? 0d : Collections.max(scoreMap.values());

            List<String> assessmentAdditionProps = additionalProps.getOrDefault("assessment", new ArrayList<>());
            Map<String, Object> addProps = new HashMap<>();
            if (!assessmentAdditionProps.isEmpty() && assessmentAdditionProps.contains("score")) {
                addProps.put("score", scoreMap);
            }

            if (isValidAssessCriteria(assessmentCriteria, score)) {
                Map<String, Object> assessedUserMap = new HashMap<>();
                assessedUserMap.put("user", enrolledUser);
                if (!addProps.isEmpty()) {
                    assessedUserMap.put("assessment", addProps);
                }
                return assessedUserMap;
            } else {
                return Collections.singletonMap("user", "");
            }
        } else {
            return Collections.singletonMap("user", enrolledUser);
        }
    }

    public static Map<String, List<AssessmentUserAttempt>> getMaxScore(Map<String, Object> requestMap) throws BaseException {

        Map<String, Object> primaryKey = new HashMap<>();
        String courseId = (String) requestMap.get(JsonKeys.COURSE_ID);
        String userId = (String) requestMap.get(JsonKeys.USER_ID);
        String batchId = (String) requestMap.get(JsonKeys.BATCH_ID);

        String contextId = "cb:" + batchId;

        primaryKey.put("user_id", userId);
        primaryKey.put("activity_type", courseId);
        primaryKey.put("context_id", contextId);

        Response row = cassandraOperation.getRecordsByProperties(JsonKeys.COURSE_KEY_SPACE_NAME, JsonKeys.USER_ACTIVITY_AGG, primaryKey, Arrays.asList("aggregates", "agg"));
        if (row != null) {
            List<Map<String, Object>> mapList = (List<Map<String, Object>>) row.get("response");
            Map<String, Object> map = mapList.get(0);
            Map<String, Double> aggregatesRaw = (Map<String, Double>) map.get("aggregates");
            Map<String, Integer> aggRaw = (Map<String, Integer>) row.get("agg");
            Map<String, Double> agg = new HashMap<>();
            if (aggRaw != null) {
                for (Map.Entry<String, Integer> entry : aggRaw.entrySet()) {
                    agg.put(entry.getKey(), entry.getValue() != null ? entry.getValue().doubleValue() : null);
                }
            }
            // Combine maps
            Map<String, Double> aggs = new HashMap<>(agg);
            aggs.putAll(aggregatesRaw);
            Map<String, List<AssessmentUserAttempt>> userAssessments = aggs.keySet().stream().filter(key -> key.startsWith("score:")).map(key -> {
                String id = key.replaceAll("score:", "");
                double score = aggs.getOrDefault("score:" + id, 0.0);
                double totalScore = aggs.getOrDefault("max_score:" + id, 1.0);
                return new AssessmentUserAttempt(id, score, totalScore);
            }).collect(Collectors.groupingBy(AssessmentUserAttempt::getContentId, HashMap::new, Collectors.toList()));

            if (!userAssessments.isEmpty()) {
                Map<String, List<AssessmentUserAttempt>> filteredUserAssessments = userAssessments.entrySet().stream().filter(entry -> {
                    String key = entry.getKey();
                    try {
                        String metadataString = contentCache.get(key, null, 60);

                        if (metadataString != null && !metadataString.isEmpty()) {
                            Map<String, Object> metadata = mapper.readValue(metadataString, new TypeReference<Map<String, Object>>() {
                            });
                            String contentType = (String) metadata.getOrDefault("contenttype", "");
                            List<String> assessmentContentType = Arrays.asList(JsonKeys.ASSESSMENT_CONTENT_TYPE.split(","));
                            return assessmentContentType.contains(contentType);
                        } else {
                            logger.error("Suppressed exception: Metadata cache not available for: " + key);
                            return false;
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1,  // Merge function (just keeps first value if duplicate keys)
                        HashMap::new));

                // Return filtered map if not empty, otherwise empty map
                return !filteredUserAssessments.isEmpty() ? filteredUserAssessments : new HashMap<>();
            }

        }
        return new HashMap<>();
    }

    public static boolean isValidAssessCriteria(Map<String, Object> assessmentCriteria, double score) {
        if (assessmentCriteria.containsKey("score") && assessmentCriteria.get("score") instanceof Number) {
            return score == ((Number) assessmentCriteria.get("score")).doubleValue();
        } else {
            Map<String, Integer> scoreCriteria = (Map<String, Integer>) assessmentCriteria.getOrDefault("score", new HashMap<>());
            if (scoreCriteria.isEmpty()) {
                return false;
            } else {
                String operation = scoreCriteria.keySet().iterator().next();
                double criteriaScore = scoreCriteria.get(operation).doubleValue();

                switch (operation) {
                    case "EQ":
                    case "eq":
                    case "=":
                        return score == criteriaScore;
                    case ">":
                        return score > criteriaScore;
                    case "<":
                        return score < criteriaScore;
                    case ">=":
                        return score >= criteriaScore;
                    case "<=":
                        return score <= criteriaScore;
                    case "ne":
                    case "!=":
                        return score != criteriaScore;
                    default:
                        return false;
                }
            }
        }
    }

    private Map<String, Object> validateUser(String userId, Map<String, Object> userCriteria, Map<String, List<String>> additionalProps) throws UnirestException, IOException {
        if (userId != null && !userId.isEmpty()) {
            String url = propertiesCache.getProperty("learner_basePath") + propertiesCache.getProperty("user_read_api") + "/" + userId + "?organisations,roles,locations,declarations,externalIds";

            Map<String, Object> result = getAPICall(url);

            if (userCriteria.isEmpty() || userCriteria.entrySet().stream().allMatch(entry -> entry.getValue().equals(result.getOrDefault(entry.getKey(), null)))) {
                Map<String, Object> resultObject = (Map<String, Object>) result.get(JsonKeys.RESULT);
                Map<String, Object> responseObject = (Map<String, Object>) resultObject.get(JsonKeys.RESPONSE);
                return responseObject;
            } else {
                return Collections.emptyMap();
            }
        } else {
            return Collections.emptyMap();
        }
    }

    private Map<String, Object> generateCertificateEvent(Map<String, Object> requestMap, Map<String, Object> template, Map<String, Object> userDetails, String certName, Map<String, Object> enrolledUser) throws UnirestException, IOException {
        String firstName = (String) userDetails.getOrDefault("firstName", "");
        String lastName = (String) userDetails.getOrDefault("lastName", "");

        String recipientName = StringUtils.isNotBlank(lastName) ? firstName + " " + lastName : firstName;
        recipientName = recipientName.trim();

        Map<String, Object> courseInfo = getCourseInfo((String) requestMap.get(JsonKeys.COURSE_ID));
        String mutiLingualCourseId = null;
        if (StringUtils.isNotBlank((String) enrolledUser.get(JsonKeys.COURSE_COMPLETION_LANGUAGE))) {
            Object languageMapV1Obj = courseInfo.get("languageMapV1");
            if (languageMapV1Obj instanceof Map<?, ?> languageMapV1) {
                String languageCode = (String) enrolledUser.get(JsonKeys.COURSE_COMPLETION_LANGUAGE);
                Object metaDataObj = languageMapV1.get(languageCode);
                if (metaDataObj instanceof Map<?, ?> metaDataMap) {
                    Map<String, Object> metaDataInfo = metaDataMap.entrySet().stream()
                            .filter(e -> e.getKey() instanceof String)
                            .collect(Collectors.toMap(
                                    e -> (String) e.getKey(),
                                    Map.Entry::getValue
                            ));

                    if (MapUtils.isNotEmpty(metaDataInfo)) {
                        mutiLingualCourseId = (String)metaDataInfo.get(JsonKeys.ID);
                        if (StringUtils.isNotBlank(mutiLingualCourseId) && !((String)courseInfo.get(JsonKeys.COURSE_ID)).equalsIgnoreCase(mutiLingualCourseId)) {
                            courseInfo = getCourseInfo(mutiLingualCourseId);
                        }
                    }
                }
            }
        }
        String courseName = (String) courseInfo.getOrDefault("courseName", "");

        SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");

        List<String> parentCollections = Optional.ofNullable((List<String>) courseInfo.get("parentCollections")).orElse(Collections.emptyList());

        Map<String, Object> eData = new HashMap<>();
        eData.put("issuedDate", dateFormatter.format(enrolledUser.get(JsonKeys.ISSUED_ON)));
        String finalRecipientName = recipientName;
        eData.put("data", Collections.singletonList(new HashMap<String, Object>() {{
            put("recipientName", finalRecipientName);
            put("recipientId", requestMap.get(JsonKeys.USER_ID));
        }}));
        eData.put("reIssueDate", "");
        eData.put("criteria", Collections.singletonMap("narrative", certName));
        eData.put("svgTemplate", template.getOrDefault("url", ""));
        eData.put("oldId", enrolledUser.get("oldId"));
        eData.put("templateId", template.getOrDefault("identifier", ""));
        eData.put("userId", requestMap.get(JsonKeys.USER_ID));
        eData.put("orgId", userDetails.getOrDefault("rootOrgId", ""));
        eData.put("issuer", mapper.readValue((String) template.getOrDefault(JsonKeys.ISSUER, "{}"), Map.class));
        eData.put("signatoryList", mapper.readValue((String) template.getOrDefault(template.get(JsonKeys.SIGNATORY_LIST), "[]"), List.class));
        eData.put("courseName", courseName);
        eData.put("basePath", propertiesCache.getProperty("cert_domain_url") + "/certs");
        eData.put("name", certName);
        eData.put("providerName", courseInfo.getOrDefault("providerName", ""));
        eData.put("tag", requestMap.get(JsonKeys.BATCH_ID));
        eData.put("primaryCategory", courseInfo.getOrDefault("primaryCategory", ""));
        eData.put("parentCollections", parentCollections);
        eData.put("coursePosterImage", courseInfo.getOrDefault("coursePosterImage", ""));
        return eData;
    }

    public Map<String, Object> getAPICall(String url) throws UnirestException, IOException {
        Map<String, String> defaultHeader = new HashMap<>();
        defaultHeader.put("Content-Type", "application/json");
        String response = HttpUtil.sendGetRequest(url, defaultHeader);
        Map<String, Object> data = mapper.readValue(response, Map.class);
        if (MapUtils.isNotEmpty(data)) {
            return data;
        } else {
            throw new RuntimeException("Error from get API: " + url + ", with response: " + response);
        }
    }

    public Map<String, Object> getCourseInfo(String courseId) throws UnirestException, IOException {

        String courseMetadataString = contentCache.get(courseId, null, 0);
        if (StringUtils.isBlank(courseMetadataString)) {
            String url = PropertiesCache.getInstance().getProperty("content_basePath") + PropertiesCache.getInstance().getProperty("content_read_api") + "/" + courseId + "?fields=name,parentCollections,primaryCategory,posterImage,organisation,contentType,languageMapV1,category,badgeDetails_v1";

            Map<String, Object> responseObject = getAPICall(url);
            Map<String, Object> resultObject = (Map<String, Object>) responseObject.get(JsonKeys.RESULT);
            if (MapUtils.isNotEmpty(resultObject)) {
                Map<String, Object> response = (Map<String, Object>) resultObject.get(JsonKeys.CONTENT);
                String courseName = sanitizeString((String) response.getOrDefault("name", ""));
                String primaryCategory = sanitizeString((String) response.getOrDefault("primaryCategory", ""));
                String posterImage = sanitizeString((String) response.getOrDefault("posterImage", ""));

                List<String> parentCollections = (List<String>) response.getOrDefault("parentCollections", new ArrayList<>());
                String category = sanitizeString((String) response.getOrDefault("category", ""));

                List<Object> orgData = (List<Object>) response.getOrDefault("organisation", Collections.emptyList());
                String contentType = sanitizeString((String) response.getOrDefault("contentType", ""));
                Map<String, Object> languageMapV1 = (Map<String, Object>) response.getOrDefault("languageMapV1", new HashMap<>());
                List<Map<String, Object>> badgeMapV1 = (List<Map<String, Object>>) response.getOrDefault("badgeDetails_v1", Collections.emptyList());
                String providerName = extractProviderName(orgData);

                Map<String, Object> courseInfoMap = new HashMap<>();
                courseInfoMap.put("courseId", courseId);
                courseInfoMap.put("courseName", courseName);
                courseInfoMap.put("parentCollections", parentCollections);
                courseInfoMap.put("primaryCategory", primaryCategory);
                courseInfoMap.put("coursePosterImage", posterImage);
                courseInfoMap.put("providerName", providerName);
                courseInfoMap.put("contentType", contentType);
                courseInfoMap.put("category", category);

                if (MapUtils.isNotEmpty(languageMapV1)) {
                    courseInfoMap.put("languageMapV1", languageMapV1);
                }
                if (CollectionUtils.isNotEmpty(badgeMapV1)) {
                    courseInfoMap.put("badgeDetails_v1", badgeMapV1);
                }
                return courseInfoMap;
            } else {
                return new HashMap<>();
            }

        } else {
            Map<String, Object> courseMetadata = mapper.readValue(courseMetadataString, Map.class);
            String courseName = sanitizeString((String) courseMetadata.getOrDefault("name", ""));
            String primaryCategory = sanitizeString((String) courseMetadata.getOrDefault("primaryCategory", ""));
            List<String> parentCollections = (List<String>) courseMetadata.getOrDefault("parentCollections", new ArrayList<>());
            String posterImage = sanitizeString((String) courseMetadata.getOrDefault("posterImage", ""));

            List<Object> orgData = (List<Object>) courseMetadata.getOrDefault("organisation", Collections.emptyList());
            String contentType = sanitizeString((String) courseMetadata.getOrDefault("contentType", ""));
            Map<String, Object> languageMapV1 = (Map<String, Object>) courseMetadata.getOrDefault("languageMapV1", new HashMap<>());
            String category = sanitizeString((String) courseMetadata.getOrDefault("category", ""));
            List<Map<String, Object>> badgeMapV1 = (List<Map<String, Object>>) courseMetadata.getOrDefault("badgeDetails_v1", Collections.emptyList());
            String providerName = extractProviderName(orgData);

            Map<String, Object> courseInfoMap = new HashMap<>();
            courseInfoMap.put("courseId", courseId);
            courseInfoMap.put("courseName", courseName);
            courseInfoMap.put("parentCollections", parentCollections);
            courseInfoMap.put("primaryCategory", primaryCategory);
            courseInfoMap.put("coursePosterImage", posterImage);
            courseInfoMap.put("providerName", providerName);
            courseInfoMap.put("contentType", contentType);
            courseInfoMap.put("category", category);
            if (MapUtils.isNotEmpty(languageMapV1)) {
                courseInfoMap.put("languageMapV1", languageMapV1);
            }
            if (CollectionUtils.isNotEmpty(badgeMapV1)) {
                courseInfoMap.put("badgeDetails_v1", badgeMapV1);
            }

            return courseInfoMap;
        }
    }

    private static String sanitizeString(String input) {
        return Optional.ofNullable(input).map(str -> str.chars().filter(c -> c >= ' ').mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining())).orElse("");
    }

    private static String extractProviderName(List<Object> orgData) {
        if (!orgData.isEmpty()) {
            String pm = orgData.get(0).toString();
            return pm;
        }
        return "";
    }

    public boolean isUserEligibleForContentCertificate(Response row) throws BaseException {
        if (row != null) {
            List<Map<String, Object>> mapList = (List<Map<String, Object>>) row.get(JsonKeys.RESPONSE);
            if (CollectionUtils.isNotEmpty(mapList)) {
                Map<String, Object> map = mapList.stream().filter(m -> Boolean.TRUE.equals(m.get(JsonKeys.ACTIVE))).findFirst().orElse(null);
                if (MapUtils.isNotEmpty(map)) {
                    int status = (int) map.getOrDefault(JsonKeys.STATUS, 0);
                    Date competedOn = (Date) map.get(JsonKeys.COMPLETED_ON);
                    if (status == 2 && competedOn != null) {
                        return true;
                    }
                }

            }
        }
        return false;
    }

    public Response fetchContentTemplate(String courseId, String batchId) throws BaseException {
        Map<String, Object> primaryKey = new HashMap<>();
        primaryKey.put(JsonKeys.COURSE_ID, courseId);
        primaryKey.put(JsonKeys.BATCH_ID, batchId);
        return cassandraOperation.getRecordsByProperties(JsonKeys.COURSE_KEY_SPACE_NAME, JsonKeys.COURSE_BATCH, primaryKey, Arrays.asList(JsonKeys.CERT_TEMPLATES));
    }
}

