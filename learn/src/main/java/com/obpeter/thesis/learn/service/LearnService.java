package com.obpeter.thesis.learn.service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.gson.Gson;
import com.obpeter.thesis.learn.client.ESAccess;
import com.obpeter.thesis.learn.entity.Command;
import com.obpeter.thesis.learn.entity.Habit;
import com.obpeter.thesis.learn.entity.HabitEqualsProperty;
import com.obpeter.thesis.learn.entity.HabitProperty;
import com.obpeter.thesis.learn.entity.HabitRangeProperty;
import com.obpeter.thesis.learn.repository.CommandRepository;
import com.obpeter.thesis.learn.repository.HabitRepository;
import com.obpeter.thesis.learn.util.RandomForestMapping;
import lombok.SneakyThrows;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.javatuples.Pair;
import org.javatuples.Quartet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class LearnService {

    public static final double PERCENTILE = 10;

    public static final long THRESHOLD_TO_LEARN = 10;

    @Autowired
    ESAccess access;

    @Autowired
    CommandRepository commandRepository;

    @Autowired
    HabitRepository habitRepository;

    private final Gson gson = new Gson();

    ArrayList<Quartet<String, Class<?>, Method, RandomForestMapping.Strategy>> properties = new ArrayList<>();

    public long count() {
        BoolQueryBuilder query = QueryBuilders.boolQuery().must(QueryBuilders.matchQuery("dayOfWeek", "SUNDAY"));
        return access.count("shm", query);
    }

    @SneakyThrows
    public ArrayList<Quartet<String, Class<?>, Method, RandomForestMapping.Strategy>> getProperties() {
        if (!properties.isEmpty()) {
            return properties;
        }
        Field[] fields = Command.class.getDeclaredFields();
        for (Field field : fields) {
            String fieldName = field.getName();
            RandomForestMapping.Strategy strategy = field.getAnnotation(RandomForestMapping.class).strategy();
            if (Arrays.asList("freeText", "time").contains(fieldName) || strategy == RandomForestMapping.Strategy.IGNORE) {
                continue;
            }
            String name = fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
            properties.add(new Quartet<>(fieldName, field.getType(),
                    Command.class.getDeclaredMethod("get" + name),
                    strategy));
        }
        return properties;
    }

    public List<Command> getAllCommands() {
        return StreamSupport.stream(commandRepository.findAll().spliterator(), false).collect(Collectors.toList());
    }

    public void getOneField() {
        BoolQueryBuilder query = QueryBuilders.boolQuery().must(QueryBuilders.matchQuery("dayOfWeek", "SUNDAY"));
        access.getField("shm", query, "dayOfWeek");
    }

    public Pair<Habit, Long> getHabitByFreeText(String freeText) {
        ArrayList<Quartet<String, Class<?>, Method, RandomForestMapping.Strategy>> properties = getProperties();
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery().must(QueryBuilders.matchPhraseQuery("freeText", freeText));
        ArrayList<HabitProperty> habitProperties = new ArrayList<>();
        while (properties.size() > 0) {
            Pair<Quartet<String, Class<?>, Method, RandomForestMapping.Strategy>, Pair<QueryBuilder, Long>> bestProperty = properties
                    .stream()
                    .map(property -> Pair.with(property,
                            propertyScore(property.getValue0(), queryBuilder, property.getValue3(), property.getValue1())))
                    .max(
                            Comparator.comparing(pair -> pair.getValue1().getValue1())).get();
            queryBuilder.must(bestProperty.getValue1().getValue0());
            switch (bestProperty.getValue0().getValue3()) {
            case RANGE:
                habitProperties.add(new HabitRangeProperty(bestProperty.getValue0().getValue0(),
                        ((RangeQueryBuilder) bestProperty.getValue1().getValue0()).from().toString(),
                        ((RangeQueryBuilder) bestProperty.getValue1().getValue0()).to().toString()));
                break;
            case EQUALS:
                habitProperties.add(new HabitEqualsProperty(bestProperty.getValue0().getValue0(),
                        ((MatchQueryBuilder) bestProperty.getValue1().getValue0()).value().toString()));
                break;
            }
            properties.removeIf(property -> property.getValue0().equals(bestProperty.getValue0().getValue0()));
        }
        return Pair.with(Habit.builder().freeText(freeText).properties(habitProperties).build(),
                access.count("shm", queryBuilder));
    }

    public <T> Pair<QueryBuilder, Long> propertyScore(String name, BoolQueryBuilder baseQuery,
            RandomForestMapping.Strategy strategy, Class<T> clazz) {
        switch (strategy) {
        case EQUALS:
            BoolQueryBuilder currentQuery = QueryBuilders.boolQuery().must(baseQuery);
            Set<T> unique = access.getField("shm", baseQuery, name).stream().map(json -> gson.fromJson(json, clazz)).collect(
                    Collectors.toSet());
            T bestValue = null;
            long score = 0;
            for (T value : unique) {
                currentQuery.must(QueryBuilders.matchQuery(name, value.toString()));
                long currentScore = access.count("shm", currentQuery);
                if (score < currentScore) {
                    bestValue = value;
                    score = currentScore;
                }
                currentQuery = QueryBuilders.boolQuery().must(baseQuery);
            }
            if (bestValue != null) {
                return Pair.with(QueryBuilders.matchQuery(name, bestValue.toString()), score);
            } else {
                return Pair.with(null, 0L);
            }
        case RANGE:
            long count = access.count("shm", baseQuery);
            RangeQueryBuilder rangeQueryBuilder;
            if (!clazz.equals(LocalDateTime.class)) {
                rangeQueryBuilder = QueryBuilders.rangeQuery(name)
                        .gte(gson.fromJson(
                                access.getPosInSorted("shm", baseQuery, name, (int) Math.ceil((PERCENTILE / 100) * count),
                                        SortOrder.ASC), clazz))
                        .lte(gson.fromJson(
                                access.getPosInSorted("shm", baseQuery, name, (int) Math.ceil((PERCENTILE / 100) * count),
                                        SortOrder.DESC), clazz));
            } else {
                rangeQueryBuilder = QueryBuilders.rangeQuery(name)
                        .gte(LocalDateTime.parse(access
                                        .getPosInSorted("shm", baseQuery, name, (int) Math.ceil((PERCENTILE / 100) * count),
                                                SortOrder.ASC).replace("\"", ""),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")))
                        .lte(LocalDateTime.parse(
                                access.getPosInSorted("shm", baseQuery, name, (int) Math.ceil((PERCENTILE / 100) * count),
                                        SortOrder.DESC).replace("\"", ""),
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")));
            }
            return Pair.with(rangeQueryBuilder,
                    access.count("shm", QueryBuilders.boolQuery().must(baseQuery).must(rangeQueryBuilder)));
        default:
            return Pair.with(null, 0L);
        }
    }

    private Stream<Command> getRecentCommands() {
        return StreamSupport.stream(commandRepository.search(QueryBuilders.boolQuery()
                .must(QueryBuilders.rangeQuery("time").gte(LocalDateTime.now().minusDays(1)))).spliterator(),true);
    }

    @Scheduled(fixedRate = 24 * 60 * 60 * 1000)
    public void learnNewCommands() {
        List<Habit> newHabits = getRecentCommands().map(command -> getHabitByFreeText(command.getFreeText()))
                .filter(pair -> pair.getValue1() >= THRESHOLD_TO_LEARN)
                .peek(pair->habitRepository.index(pair.getValue0()))
                .map(Pair::getValue0).collect(Collectors.toList());
    }

}
