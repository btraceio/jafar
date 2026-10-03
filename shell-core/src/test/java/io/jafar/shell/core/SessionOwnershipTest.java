package io.jafar.shell.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionOwnershipTest {

  private SessionOwnership ownership;

  @BeforeEach
  void setUp() {
    ownership = new SessionOwnership();
    RequestScope.clear();
  }

  @AfterEach
  void tearDown() {
    RequestScope.clear();
  }

  private void as(String scope) {
    RequestScope.set(scope);
  }

  @Test
  void aSessionIsVisibleToItsOwnerAndHiddenFromOtherClients() {
    as("A");
    ownership.add(1, null);

    assertEquals(1, ownership.resolve("1"));
    as("B");
    assertNull(ownership.resolve("1"), "B must not reach A's session by guessing its id");
    assertFalse(ownership.isVisible(1));
  }

  @Test
  void twoClientsCanUseTheSameAlias() {
    as("A");
    ownership.add(1, "cpu");
    as("B");
    ownership.add(2, "cpu");

    assertEquals(2, ownership.resolve("cpu"), "B's alias resolves to B's session");
    as("A");
    assertEquals(1, ownership.resolve("cpu"), "A's alias resolves to A's session");
  }

  @Test
  void aClientCannotReuseItsOwnAlias() {
    as("A");
    ownership.add(1, "cpu");

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> ownership.requireAliasFree("cpu"));

    assertTrue(e.getMessage().contains("Alias already in use: cpu"));
    assertThrows(IllegalArgumentException.class, () -> ownership.add(2, "cpu"));
  }

  @Test
  void anAliasFreedByRemovalCanBeReused() {
    as("A");
    ownership.add(1, "cpu");
    ownership.remove(1);

    ownership.requireAliasFree("cpu");
    ownership.add(2, "cpu");

    assertEquals(2, ownership.resolve("cpu"));
  }

  @Test
  void unownedSessionsAreVisibleToEveryone() {
    ownership.addUnowned(7, "restored");

    as("A");
    assertEquals(7, ownership.resolve("7"));
    assertEquals(7, ownership.resolve("restored"));
    as("B");
    assertEquals(7, ownership.resolve("restored"));
  }

  @Test
  void aClientsOwnAliasWinsOverAnUnownedOneOfTheSameName() {
    ownership.addUnowned(7, "cpu");
    as("A");
    ownership.add(1, "cpu");

    assertEquals(1, ownership.resolve("cpu"));
    as("B");
    assertEquals(7, ownership.resolve("cpu"), "B has none of its own, so it sees the shared one");
  }

  @Test
  void visibleIdsAreTheCallersOwnPlusUnownedInOrder() {
    ownership.addUnowned(1, null);
    as("A");
    ownership.add(2, null);
    as("B");
    ownership.add(3, null);

    as("A");
    assertEquals(List.of(1, 2), ownership.visibleIds(List.of(1, 2, 3)));
    as("B");
    assertEquals(List.of(1, 3), ownership.visibleIds(List.of(1, 2, 3)));
  }

  @Test
  void closableByCallerIsItsOwnPlusSharedSessionsNeverAnotherClients() {
    ownership.addUnowned(1, null);
    as("A");
    ownership.add(2, null);
    as("B");
    ownership.add(3, null);

    as("A");
    assertEquals(
        List.of(1, 2),
        ownership.closableByCaller(List.of(1, 2, 3)),
        "closing all means everything the caller can see: its own and the shared ones, not B's");
  }

  @Test
  void anUnscopedCallerIsTheServerItselfAndSeesAndClosesEverything() {
    ownership.addUnowned(1, null);
    as("A");
    ownership.add(2, "cpu");
    RequestScope.clear();

    assertEquals(List.of(1, 2), ownership.visibleIds(List.of(1, 2)));
    assertEquals(List.of(1, 2), ownership.closableByCaller(List.of(1, 2)));
    assertEquals(2, ownership.resolve("2"));
    assertEquals(2, ownership.resolve("cpu"), "an alias is found whichever client holds it");
  }

  @Test
  void sessionsOfClientsThatAreGoneAreReportedForRelease() {
    ownership.addUnowned(1, null);
    as("A");
    ownership.add(2, null);
    as("B");
    ownership.add(3, null);
    as("C");
    ownership.add(4, null);

    List<Integer> gone = ownership.ownedByScopesOutside(Set.of("B"));

    assertEquals(List.of(2, 4), gone);
    assertFalse(gone.contains(1), "unowned sessions belong to no client and are never released");
  }

  @Test
  void removingAnIdForgetsItsOwnerAndAlias() {
    as("A");
    ownership.add(1, "cpu");
    ownership.remove(1);

    assertNull(ownership.resolve("1"));
    assertNull(ownership.resolve("cpu"));
    assertEquals(List.of(), ownership.ownedByScopesOutside(Set.of()));
  }
}
