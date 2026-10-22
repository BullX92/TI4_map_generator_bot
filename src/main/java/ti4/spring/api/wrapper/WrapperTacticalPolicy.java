package ti4.spring.api.wrapper;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import ti4.game.Game;
import ti4.game.Player;
import ti4.game.Tile;
import ti4.helpers.ButtonHelper;
import ti4.helpers.CommandCounterHelper;
import ti4.helpers.FoWHelper;
import ti4.helpers.Units.UnitType;

final class WrapperTacticalPolicy {
    static final String PLAN = "wrapperTacticalPlan";

    record Plan(String playerId, String origin, String sourcePlanet, String target, String planet, int stage) {
        String encode() {
            return String.join(";", playerId, origin, sourcePlanet, target, planet, Integer.toString(stage));
        }

        Plan next() {
            return new Plan(playerId, origin, sourcePlanet, target, planet, stage + 1);
        }

        List<String> buttons(Player player) {
            return switch (stage) {
                case 0 -> List.of("tacticalAction");
                case 1 -> List.of("ring_" + target.charAt(0));
                case 2 -> List.of("ringTile_" + target);
                case 3 -> List.of("tacticalMoveFrom_" + origin);
                case 4 -> List.of("unitTacticalMove_" + origin + "_1_cv_" + player.getColor());
                case 5 -> List.of("unitTacticalMove_" + origin + "_1_gf_" + sourcePlanet + "_" + player.getColor());
                case 6 -> List.of("doneWithOneSystem_" + origin);
                case 7 -> List.of("concludeMove_" + target);
                case 8 -> List.of("landUnits_" + target + "_1gf_" + planet + "_" + player.getColor());
                case 9 -> List.of("doneLanding_" + target);
                case 10 -> List.of("doneWithTacticalAction");
                case 11 -> List.of("turnEnd", "endOfTurnAbilities");
                case 12 -> List.of("turnEnd");
                default -> List.of();
            };
        }
    }

    static Plan current(Game game, Player player) {
        String saved = game.getStoredValue(PLAN);
        if (saved.isEmpty()) return choose(game, player);
        String[] fields = saved.split(";");
        if (fields.length != 6 || !fields[0].equals(player.getUserID())) return null;
        return new Plan(fields[0], fields[1], fields[2], fields[3], fields[4], Integer.parseInt(fields[5]));
    }

    static Plan choose(Game game, Player player) {
        if (player.getTacticalCC() < 1
                || game.isFowMode()
                || game.getTileMap().containsKey(game.getCurrentActiveSystem())
                || !game.getTacticalActionDisplacement().isEmpty()) return null;
        for (Tile origin : game.getTileMap().values().stream()
                .sorted(Comparator.comparing(Tile::getPosition))
                .toList()) {
            if (CommandCounterHelper.hasCC(null, player.getColor(), origin)) continue;
            if (origin.getSpaceUnitHolder().getUnitCount(UnitType.Carrier, player) < 1) continue;
            var source = origin.getPlanetUnitHolders().stream()
                    .filter(planet -> player.getPlanets().contains(planet.getName()))
                    .filter(planet -> planet.getUnitCount(UnitType.Infantry, player) > 0)
                    .sorted(Comparator.comparing(planet -> planet.getName()))
                    .findFirst()
                    .orElse(null);
            if (source == null) continue;
            for (Tile target : FoWHelper.getAdjacentTiles(game, origin.getPosition(), player, false).stream()
                    .map(game::getTileByPosition)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(Tile::getPosition))
                    .toList()) {
                if (target == origin
                        || target.isHomeSystem(game)
                        || target.isMecatol(game)
                        || target.isAnomaly(game, player)
                        || target.getUnitHolders().values().stream().anyMatch(holder -> holder.getUnitCount() > 0)
                        || !ButtonHelper.canActivateTile(game, player, target)
                        || FoWHelper.otherPlayersHaveUnitsInSystem(player, target, game)) continue;
                var planet = target.getPlanetUnitHolders().stream()
                        .filter(holder -> game.getRealPlayers().stream()
                                .noneMatch(owner -> owner.getPlanets().contains(holder.getName())))
                        .sorted(Comparator.comparing(holder -> holder.getName()))
                        .findFirst()
                        .orElse(null);
                if (planet != null)
                    return new Plan(
                            player.getUserID(),
                            origin.getPosition(),
                            source.getName(),
                            target.getPosition(),
                            planet.getName(),
                            0);
            }
        }
        return null;
    }

    static boolean changed(
            Game game, Player player, Plan plan, int beforeTokens, int beforeCarriers, int beforeInfantry) {
        Tile origin = game.getTileByPosition(plan.origin());
        Tile target = game.getTileByPosition(plan.target());
        return switch (plan.stage()) {
            case 2 -> plan.target().equals(game.getActiveSystem()) && player.getTacticalCC() == beforeTokens - 1;
            case 4 -> origin.getSpaceUnitHolder().getUnitCount(UnitType.Carrier, player) == beforeCarriers - 1;
            case 5 ->
                origin.getUnitHolders().get(plan.sourcePlanet()).getUnitCount(UnitType.Infantry, player)
                        == beforeInfantry - 1;
            case 7 ->
                target.getSpaceUnitHolder().getUnitCount(UnitType.Carrier, player) > 0
                        && target.getSpaceUnitHolder().getUnitCount(UnitType.Infantry, player) > 0;
            case 8 -> target.getUnitHolders().get(plan.planet()).getUnitCount(UnitType.Infantry, player) > 0;
            case 9 -> player.getPlanets().contains(plan.planet());
            case 12 -> !player.getUserID().equals(game.getActivePlayerID());
            default -> true;
        };
    }
}
