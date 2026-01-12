package io.jafar.shell.llm;

import io.jafar.shell.llm.LLMProvider.Message;
import io.jafar.shell.llm.LLMProvider.Role;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maintains conversation history for context-aware LLM interactions. Stores user queries, LLM
 * responses, and generated JfrPath queries. Thread-safe for concurrent access.
 */
public class ConversationHistory {

  private final Deque<Turn> history;
  private final int maxTurns;

  /**
   * Creates a new conversation history with the specified maximum number of turns.
   *
   * @param maxTurns maximum number of turns to keep (older turns are pruned)
   */
  public ConversationHistory(int maxTurns) {
    this.history = new ArrayDeque<>(maxTurns);
    this.maxTurns = maxTurns;
  }

  /** Creates a new conversation history with default capacity (20 turns). */
  public ConversationHistory() {
    this(20);
  }

  /**
   * Adds a new turn to the conversation history.
   *
   * @param turn the turn to add
   */
  public synchronized void addTurn(Turn turn) {
    history.addLast(turn);
    // Prune old turns if we exceed maxTurns
    while (history.size() > maxTurns) {
      history.removeFirst();
    }
  }

  /**
   * Converts the conversation history to a list of LLM messages for context inclusion.
   *
   * @return list of messages alternating between USER and ASSISTANT
   */
  public synchronized List<Message> toMessages() {
    List<Message> messages = new ArrayList<>();
    for (Turn turn : history) {
      messages.add(new Message(Role.USER, turn.userQuery));
      if (turn.assistantResponse != null) {
        messages.add(new Message(Role.ASSISTANT, turn.assistantResponse));
      }
    }
    return messages;
  }

  /**
   * Gets the number of turns in the history.
   *
   * @return number of turns
   */
  public synchronized int size() {
    return history.size();
  }

  /** Clears all turns from the history. */
  public synchronized void clear() {
    history.clear();
  }

  /**
   * Prunes the history to a maximum number of turns.
   *
   * @param maxTurns maximum turns to keep
   */
  public synchronized void prune(int maxTurns) {
    while (history.size() > maxTurns) {
      history.removeFirst();
    }
  }

  /**
   * Serializes this history to a Map for storage in session variables.
   *
   * @return map representation
   */
  public synchronized Map<String, Object> toMap() {
    Map<String, Object> map = new HashMap<>();
    map.put("maxTurns", maxTurns);

    List<Map<String, Object>> turns = new ArrayList<>();
    for (Turn turn : history) {
      Map<String, Object> turnMap = new HashMap<>();
      turnMap.put("userQuery", turn.userQuery);
      turnMap.put("assistantResponse", turn.assistantResponse);
      turnMap.put("generatedQuery", turn.generatedQuery.orElse(null));
      turnMap.put("timestamp", turn.timestamp.toString());
      turns.add(turnMap);
    }
    map.put("turns", turns);

    return map;
  }

  /**
   * Deserializes a conversation history from a Map.
   *
   * @param map map representation
   * @return conversation history
   */
  @SuppressWarnings("unchecked")
  public static ConversationHistory fromMap(Map<String, Object> map) {
    int maxTurns = (int) map.getOrDefault("maxTurns", 20);
    ConversationHistory history = new ConversationHistory(maxTurns);

    List<Map<String, Object>> turns = (List<Map<String, Object>>) map.get("turns");
    if (turns != null) {
      for (Map<String, Object> turnMap : turns) {
        String userQuery = (String) turnMap.get("userQuery");
        String assistantResponse = (String) turnMap.get("assistantResponse");
        String generatedQuery = (String) turnMap.get("generatedQuery");
        String timestampStr = (String) turnMap.get("timestamp");

        Instant timestamp = Instant.parse(timestampStr);
        Turn turn =
            new Turn(userQuery, assistantResponse, Optional.ofNullable(generatedQuery), timestamp);
        history.addTurn(turn);
      }
    }

    return history;
  }

  /**
   * Gets a formatted summary of the conversation history for display.
   *
   * @return formatted summary
   */
  public synchronized String getSummary() {
    if (history.isEmpty()) {
      return "No conversation history";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("Conversation history (").append(history.size()).append(" turns):\n");

    int i = 1;
    for (Turn turn : history) {
      sb.append(String.format("\n[%d] %s\n", i++, turn.timestamp));
      sb.append("User: ").append(truncate(turn.userQuery, 80)).append("\n");
      if (turn.generatedQuery.isPresent()) {
        sb.append("Query: ").append(truncate(turn.generatedQuery.get(), 80)).append("\n");
      }
      if (turn.assistantResponse != null) {
        sb.append("Assistant: ").append(truncate(turn.assistantResponse, 80)).append("\n");
      }
    }

    return sb.toString();
  }

  /**
   * Truncates a string to a maximum length with ellipsis.
   *
   * @param str string to truncate
   * @param maxLength maximum length
   * @return truncated string
   */
  private String truncate(String str, int maxLength) {
    if (str == null || str.length() <= maxLength) {
      return str;
    }
    return str.substring(0, maxLength - 3) + "...";
  }

  /**
   * A single turn in a conversation.
   *
   * @param userQuery the user's natural language query
   * @param assistantResponse the LLM's response (explanation, etc.)
   * @param generatedQuery the JfrPath query that was generated
   * @param timestamp when this turn occurred
   */
  public record Turn(
      String userQuery,
      String assistantResponse,
      Optional<String> generatedQuery,
      Instant timestamp) {

    /**
     * Creates a new turn with current timestamp.
     *
     * @param userQuery user's query
     * @param assistantResponse assistant's response
     * @param generatedQuery generated JfrPath query
     */
    public Turn(String userQuery, String assistantResponse, Optional<String> generatedQuery) {
      this(userQuery, assistantResponse, generatedQuery, Instant.now());
    }
  }
}
