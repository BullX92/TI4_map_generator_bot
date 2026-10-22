package ti4.spring.api.wrapper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.Units;
import ti4.helpers.Units.UnitType;
import ti4.image.Mapper;
import ti4.testUtils.BaseTi4Test;

class WrapperTacticalPolicyTest extends BaseTi4Test {
    @Test
    void choosesAdjacentUnclaimedPlanetAndPreservesPlanAcrossReloadedState() {
        Game game = game();
        Player player = game.getPlayer("agent");
        var plan = WrapperTacticalPolicy.choose(game, player);
        assertNotNull(plan);
        assertEquals("201", plan.target());
        assertEquals("wellon", plan.planet());
        game.setStoredValue(WrapperTacticalPolicy.PLAN, plan.next().encode());
        assertEquals(plan.next(), WrapperTacticalPolicy.current(game, player));
        assertNull(WrapperTacticalPolicy.current(game, game.addPlayer("human", "Human")));
    }

    @Test
    void refusesLockedOriginsOccupiedTargetsAndOwnedPlanets() {
        Game game = game();
        Player player = game.getPlayer("agent");
        game.getTileByPosition("301").addCC(Mapper.getCCID("blue"));
        assertNull(WrapperTacticalPolicy.choose(game, player));
        game = game();
        player = game.getPlayer("agent");
        game.getTileByPosition("201").getSpaceUnitHolder().addUnit(Units.getUnitKey(UnitType.Cruiser, "red"), 1);
        assertNull(WrapperTacticalPolicy.choose(game, player));
        game = game();
        Player enemy = game.addPlayer("enemy", "Enemy");
        enemy.setFaction("hacan");
        enemy.setColor("yellow");
        enemy.getPlanets().add("wellon");
        assertNull(WrapperTacticalPolicy.choose(game, game.getPlayer("agent")));
    }

    @Test
    void activationMovementLandingAndTurnEndRequireRealStateChanges() {
        Game game = game();
        Player player = game.getPlayer("agent");
        var plan = WrapperTacticalPolicy.choose(game, player);
        for (int stage : new int[] {2, 4, 5, 7, 8, 9, 12}) {
            game.setActivePlayerID(player.getUserID());
            var atStage = new WrapperTacticalPolicy.Plan(
                    plan.playerId(), plan.origin(), plan.sourcePlanet(), plan.target(), plan.planet(), stage);
            assertFalse(WrapperTacticalPolicy.changed(game, player, atStage, 3, 1, 2), "stage " + stage);
        }
        game.setActiveSystem("201");
        player.setTacticalCC(2);
        var activation = new WrapperTacticalPolicy.Plan(
                plan.playerId(), plan.origin(), plan.sourcePlanet(), plan.target(), plan.planet(), 2);
        assertTrue(WrapperTacticalPolicy.changed(game, player, activation, 3, 1, 2));
    }

    @Test
    void usesTheOriginalEndTurnOfferAndRetainsTheOptionalAbilityConfirmation() {
        Game game = game();
        Player player = game.getPlayer("agent");
        var original = ti4.helpers.ButtonHelper.getEndTurnButton(game, player);
        var end = new WrapperTacticalPolicy.Plan("agent", "301", "jord", "201", "wellon", 11);
        String handler = ti4.discord.interactions.routing.ComponentIdEnvelope.decode(original.getCustomId())
                .handlerId();
        assertTrue(end.buttons(player).contains(handler));
        assertEquals(java.util.List.of("turnEnd"), end.next().buttons(player));
        game.setActivePlayerID(player.getUserID());
        assertFalse(WrapperTacticalPolicy.changed(game, player, end.next(), 3, 1, 2));
        game.setActivePlayerID("human");
        assertTrue(WrapperTacticalPolicy.changed(game, player, end.next(), 3, 1, 2));
    }

    private Game game() {
        Game game = new Game();
        game.setName("tactical-policy-proof");
        game.setPhaseOfGame("action");
        Player player = game.addPlayer("agent", "Agent");
        player.setFaction("sol");
        player.setColor("blue");
        player.setTacticalCC(3);
        player.getPlanets().add("jord");
        Tile home = new Tile("01", "301");
        home.getSpaceUnitHolder().addUnit(Units.getUnitKey(UnitType.Carrier, "blue"), 1);
        home.getUnitHolders().get("jord").addUnit(Units.getUnitKey(UnitType.Infantry, "blue"), 2);
        game.setTile(home);
        game.setTile(new Tile("19", "201"));
        return game;
    }
}
