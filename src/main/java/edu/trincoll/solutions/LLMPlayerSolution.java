package edu.trincoll.solutions;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.trincoll.game.command.AttackCommand;
import edu.trincoll.game.command.GameCommand;
import edu.trincoll.game.command.HealCommand;
import edu.trincoll.game.model.Character;
import edu.trincoll.game.player.GameState;
import edu.trincoll.game.player.Player;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * SOLUTION: Complete implementation of LLMPlayer with all TODOs solved.
 *
 * This demonstrates:
 * - TODO 1: Effective prompt engineering
 * - TODO 2: Spring AI ChatClient usage with .entity() for automatic deserialization
 * - TODO 3: Converting Decision objects to GameCommands
 *
 * Design Patterns:
 * - STRATEGY: Implements Player interface with LLM-based decisions
 * - ADAPTER: Adapts LLM text output to GameCommand objects
 * - FACADE: Simplifies LLM interaction complexity
 */
public class LLMPlayerSolution implements Player {
    private final ChatClient chatClient;
    private final String modelName;

    public LLMPlayerSolution(ChatClient chatClient, String modelName) {
        this.chatClient = chatClient;
        this.modelName = modelName;
    }

    @Override
    public GameCommand decideAction(Character self,
                                   List<Character> allies,
                                   List<Character> enemies,
                                   GameState gameState) {
        try {
            // TODO 1: Build the prompt
            String prompt = buildPrompt(self, allies, enemies, gameState);

            // TODO 2: Call the LLM and get Decision object
            // Spring AI automatically deserializes JSON response to Decision record
            Decision decision = chatClient.prompt()
                .user(prompt)
                .call()
                .entity(Decision.class);

            // Validate the decision
            if (decision.action() == null || decision.target() == null) {
                System.out.println("[" + modelName + "] Invalid decision format, using fallback");
                return defaultAction(self, enemies);
            }

            // Log the LLM's reasoning
            System.out.println("[" + modelName + "] Reasoning: " + decision.reasoning());

            // TODO 3: Convert Decision to GameCommand
            // Find the target character based on action type
            Character target = decision.action().equals("attack")
                ? findCharacterByName(decision.target(), enemies)
                : findCharacterByName(decision.target(), allies);

            // Create and return the appropriate command
            return switch (decision.action().toLowerCase()) {
                case "attack" -> new AttackCommand(self, target);
                case "heal" -> new HealCommand(target, 30);
                default -> {
                    System.out.println("[" + modelName + "] Unknown action: " + decision.action());
                    yield defaultAction(self, enemies);
                }
            };

        } catch (Exception e) {
            // Handle any errors gracefully
            System.out.println("[" + modelName + "] Error: " + e.getMessage() + ", using fallback");
            return defaultAction(self, enemies);
        }
    }

    /**
     * SOLUTION for TODO 1: Build comprehensive prompt for LLM.
     * <p>
     * Key elements of an effective prompt:
     * 1. Clear role definition
     * 2. Current status with percentages
     * 3. Team status (allies)
     * 4. Enemy status
     * 5. Available actions with estimates
     * 6. Strategic guidance
     * 7. JSON format specification
     */
    private String buildPrompt(Character self,
                              List<Character> allies,
                              List<Character> enemies,
                              GameState gameState) {
        double healthPercent = (double) self.getStats().health() / self.getStats().maxHealth() * 100;

        StringBuilder prompt = new StringBuilder();

        // 1. Role definition
        prompt.append(String.format("You are %s, a %s in a tactical RPG battle.%n%n",
            self.getName(), self.getType()));

        // 2. Your status
        prompt.append("YOUR STATUS:\n");
        prompt.append(String.format("- HP: %d/%d (%.0f%%)%n",
            self.getStats().health(),
            self.getStats().maxHealth(),
            healthPercent));
        prompt.append(String.format("- Mana: %d/%d%n",
            self.getStats().mana(),
            self.getStats().maxMana()));
        prompt.append(String.format("- Attack Power: %d%n", self.getStats().attackPower()));
        prompt.append(String.format("- Defense: %d%n", self.getStats().defense()));
        prompt.append(String.format("- Attack Strategy: %s%n",
            self.getAttackStrategy().getClass().getSimpleName()));
        prompt.append(String.format("- Defense Strategy: %s%n%n",
            self.getDefenseStrategy().getClass().getSimpleName()));

        // 3. Your team (allies)
        prompt.append("YOUR TEAM (Allies):\n");
        prompt.append(formatCharacterList(allies));
        prompt.append("\n");

        // 4. Enemies
        prompt.append("ENEMIES:\n");
        prompt.append(formatCharacterList(enemies));
        prompt.append("\n");

        // 5. Available actions with damage estimates
        prompt.append("AVAILABLE ACTIONS:\n");
        Character weakestEnemy = enemies.stream()
            .filter(e -> e.getStats().health() > 0)
            .min((e1, e2) -> Integer.compare(e1.getStats().health(), e2.getStats().health()))
            .orElse(enemies.getFirst());
        int estimatedDamage = estimateDamage(self, weakestEnemy);
        prompt.append(String.format("1. attack <enemy_name> - Estimated damage to %s: ~%d HP%n",
            weakestEnemy.getName(), estimatedDamage));
        prompt.append("2. heal <ally_name> - Restores 30 HP\n\n");

        // 6. Strategic guidance
        prompt.append("TACTICAL GUIDANCE:\n");
        prompt.append("- Focus fire: Attack wounded enemies to eliminate threats quickly\n");
        prompt.append("- Protect allies: Heal teammates below 30% HP to prevent deaths\n");
        prompt.append("- Consider your role: ");
        prompt.append(switch (self.getType()) {
            case WARRIOR -> "Tank damage and protect weaker allies\n";
            case MAGE -> "Deal high damage but protect yourself\n";
            case ARCHER -> "Pick off wounded enemies from range\n";
            case ROGUE -> "Target high-value enemies quickly\n";
        });
        prompt.append(String.format("- Current turn: %d, Round: %d%n%n",
            gameState.turnNumber(), gameState.roundNumber()));

        // 7. JSON format specification
        prompt.append("Respond ONLY with valid JSON in this exact format:\n");
        prompt.append("{\n");
        prompt.append("  \"action\": \"attack\" | \"heal\",\n");
        prompt.append("  \"target\": \"exact_character_name\",\n");
        prompt.append("  \"reasoning\": \"brief tactical explanation\"\n");
        prompt.append("}\n\n");

        prompt.append("Valid enemy names: ");
        prompt.append(enemies.stream()
            .filter(e -> e.getStats().health() > 0)
            .map(Character::getName)
            .reduce((a, b) -> a + ", " + b)
            .orElse("none"));
        prompt.append("\n");

        prompt.append("Valid ally names: ");
        prompt.append(allies.stream()
            .filter(a -> a.getStats().health() > 0)
            .map(Character::getName)
            .reduce((a, b) -> a + ", " + b)
            .orElse("none"));

        return prompt.toString();
    }

    /**
     * Formats a list of characters for display in the prompt.
     */
    private String formatCharacterList(List<Character> characters) {
        StringBuilder sb = new StringBuilder();
        for (Character c : characters) {
            double healthPercent = (double) c.getStats().health() / c.getStats().maxHealth() * 100;
            String status = healthPercent < 30 ? " ⚠️ CRITICAL" :
                           healthPercent < 60 ? " ⚡ WOUNDED" : "";
            sb.append(String.format("  - %s (%s): %d/%d HP (%.0f%%)%s%n",
                c.getName(),
                c.getType(),
                c.getStats().health(),
                c.getStats().maxHealth(),
                healthPercent,
                status));
        }
        return sb.toString();
    }

    /**
     * Estimates damage this character would deal to a target.
     */
    private int estimateDamage(Character attacker, Character target) {
        int baseDamage = attacker.attack(target);
        return target.getDefenseStrategy()
            .calculateDamageReduction(target, baseDamage);
    }

    /**
     * Finds a character by name in a list.
     */
    private Character findCharacterByName(String name, List<Character> characters) {
        return characters.stream()
            .filter(c -> c.getName().equalsIgnoreCase(name))
            .filter(c -> c.getStats().health() > 0)
            .findFirst()
            .orElse(characters.stream()
                .filter(c -> c.getStats().health() > 0)
                .findFirst()
                .orElse(characters.getFirst())); // Ultimate fallback
    }

    /**
     * Default action when LLM fails or returns invalid response.
     */
    private GameCommand defaultAction(Character self, List<Character> enemies) {
        // Attack the weakest enemy
        Character target = enemies.stream()
            .filter(e -> e.getStats().health() > 0)
            .min((e1, e2) -> Integer.compare(e1.getStats().health(), e2.getStats().health()))
            .orElse(enemies.getFirst());

        return new AttackCommand(self, target);
    }

    /**
     * Record for parsing LLM JSON response.
     */
    public record Decision(
        @JsonProperty(required = true) String action,
        @JsonProperty(required = true) String target,
        @JsonProperty String reasoning
    ) {}
}
