package com.ledgerguard.postings;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the append-only property of postings at the API-surface level: there is
 * no code path a caller could use to change one.
 *
 * <p>These assertions are structural on purpose. A test that merely fails to
 * mutate a posting proves nothing about the next person who adds a setter, so
 * instead these fail the build the moment a mutation path appears.
 *
 * <p>The complementary database-level proof (that even a reflective change does
 * not produce an UPDATE) lives in {@code PaymentFlowIntegrationTest}.
 */
class PostingImmutabilityTest {

    private static final Set<String> MUTATING_METHOD_NAMES = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush",
            "delete", "deleteAll", "deleteById", "deleteAllById",
            "deleteAllInBatch", "deleteAllByIdInBatch",
            "update", "merge", "flush");

    @Test
    @DisplayName("Posting exposes no setter")
    void postingHasNoSetters() {
        List<String> setters = Arrays.stream(Posting.class.getMethods())
                .filter(method -> method.getDeclaringClass() == Posting.class)
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .toList();

        assertThat(setters)
                .as("Posting must expose no setter; found %s", setters)
                .isEmpty();
    }

    @Test
    @DisplayName("Posting exposes no method that mutates it")
    void postingHasNoMutatingMethods() {
        List<String> mutators = Arrays.stream(Posting.class.getMethods())
                .filter(method -> method.getDeclaringClass() == Posting.class)
                .map(Method::getName)
                .filter(name -> MUTATING_METHOD_NAMES.contains(name)
                        || name.startsWith("set")
                        || name.startsWith("update")
                        || name.startsWith("mark"))
                .toList();

        assertThat(mutators)
                .as("Posting must expose no mutator; found %s", mutators)
                .isEmpty();
    }

    @Test
    @DisplayName("every Posting field is private, so nothing can be reassigned from outside")
    void postingFieldsArePrivate() {
        List<String> exposed = Arrays.stream(Posting.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !Modifier.isPrivate(field.getModifiers()))
                .map(Field::getName)
                .toList();

        assertThat(exposed)
                .as("all Posting fields must be private; found non-private %s", exposed)
                .isEmpty();
    }

    @Test
    @DisplayName("every Posting column is mapped updatable = false")
    void everyColumnIsNonUpdatable() {
        List<String> updatable = Arrays.stream(Posting.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> {
                    Column column = field.getAnnotation(Column.class);
                    // A field with no @Column would default to updatable = true.
                    return column == null || column.updatable();
                })
                .map(Field::getName)
                .toList();

        assertThat(updatable)
                .as("every Posting column must be updatable = false; offending fields %s", updatable)
                .isEmpty();
    }

    @Test
    @DisplayName("PostingRepository does not inherit a save or delete path")
    void repositoryDoesNotExtendCrudRepository() {
        assertThat(CrudRepository.class.isAssignableFrom(PostingRepository.class))
                .as("PostingRepository must not extend CrudRepository, "
                        + "which would hand every caller save() and delete()")
                .isFalse();
    }

    @Test
    @DisplayName("PostingRepository declares only read methods")
    void repositoryDeclaresNoMutatingMethods() {
        List<String> mutators = Arrays.stream(PostingRepository.class.getMethods())
                .map(Method::getName)
                .filter(MUTATING_METHOD_NAMES::contains)
                .toList();

        assertThat(mutators)
                .as("PostingRepository must expose no write method; found %s", mutators)
                .isEmpty();
    }

    @Test
    @DisplayName("a created posting reports exactly what it was created with")
    void createdPostingIsFaithfulAndFixed() {
        java.util.UUID transactionId = java.util.UUID.randomUUID();
        java.util.UUID accountId = java.util.UUID.randomUUID();
        java.time.Instant createdAt = java.time.Instant.parse("2026-01-01T12:00:00Z");

        Posting posting = Posting.create(transactionId, accountId, PostingType.DEBIT, 1025L, "usd", createdAt);

        assertThat(posting.getTransactionId()).isEqualTo(transactionId);
        assertThat(posting.getAccountId()).isEqualTo(accountId);
        assertThat(posting.getType()).isEqualTo(PostingType.DEBIT);
        assertThat(posting.getAmountMinor()).isEqualTo(1025L);
        assertThat(posting.getCurrency()).isEqualTo("USD");
        assertThat(posting.getCreatedAt()).isEqualTo(createdAt);
        assertThat(posting.signedAmountMinor()).isEqualTo(1025L);
    }
}
