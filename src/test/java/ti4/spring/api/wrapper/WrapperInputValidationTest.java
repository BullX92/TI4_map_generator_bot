package ti4.spring.api.wrapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.checkboxgroup.CheckboxGroup;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.modals.Modal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ti4.game.Game;

class WrapperInputValidationTest {
    @Test
    void originalFactionSelectionRevisionSerializesOptionalDescriptionsAndDetectsChangedChoices() {
        Game game = new Game();
        var message = mock(Message.class);
        when(message.getId()).thenReturn("123");
        when(message.getContentRaw()).thenReturn("Prioritized factions");
        var menu = StringSelectMenu.create("prioritize_faction")
                .addOption("Sol", "sol")
                .addOption("Hacan", "hacan", "Trading faction")
                .setRequiredRange(1, 1)
                .build();
        when(message.getComponentTree()).thenReturn(MessageComponentTree.of(ActionRow.of(menu)));
        String first = assertDoesNotThrow(() -> WrapperInteractionService.revision(game, message));
        assertEquals(first, WrapperInteractionService.revision(game, message));
        var changed = StringSelectMenu.create("prioritize_faction")
                .addOption("Sol", "sol")
                .addOption("Jol-Nar", "jolnar")
                .setRequiredRange(1, 1)
                .build();
        when(message.getComponentTree()).thenReturn(MessageComponentTree.of(ActionRow.of(changed)));
        assertNotEquals(first, WrapperInteractionService.revision(game, message));
    }

    @Test
    void originalHolderGrantMakesSharedPrimaryAvailableOnlyForItsGrantee() {
        Game game = new Game();
        var grantee = game.addPlayer("123", "Grantee");
        grantee.setFaction("sol");
        var outsider = game.addPlayer("456", "Other");
        outsider.setFaction("hacan");
        var button = Button.primary("FFCC_onyxxa_primary", "Primary");
        var message = mock(Message.class);
        when(message.getComponentTree())
                .thenReturn(MessageComponentTree.of(ActionRow.of(button, Button.primary("sc_follow_1", "Follow"))));
        assertFalse(WrapperInteractionService.canOffer(game, message, button, grantee));
        game.setStoredValue("scPrimaryAccess_" + game.getRound() + "_1", "onyxxa;sol");
        assertTrue(WrapperInteractionService.canOffer(game, message, button, grantee));
        assertFalse(WrapperInteractionService.canOffer(game, message, button, outsider));
    }

    @Test
    void unsupportedModalInputsCannotBeSilentlyOmitted() {
        var modal = Modal.create("checkbox_form", "Options")
                .addComponents(Label.of(
                        "Options",
                        CheckboxGroup.create("options")
                                .addOption("Option", "one")
                                .build()))
                .build();
        assertFalse(WrapperInteractionService.supportsModal(modal));
        assertThrows(ResponseStatusException.class, () -> WrapperInteractionService.validateFields(modal, Map.of()));
    }

    @Test
    void selectionMustUseOriginalOptionsAndLimits() {
        var menu = StringSelectMenu.create("choose_faction")
                .addOption("Sol", "sol")
                .addOption("Hacan", "hacan")
                .setRequiredRange(1, 1)
                .build();
        assertDoesNotThrow(() -> WrapperInteractionService.validateSelection(menu, List.of("sol")));
        assertThrows(
                ResponseStatusException.class,
                () -> WrapperInteractionService.validateSelection(menu, List.of("invented")));
        assertThrows(
                ResponseStatusException.class,
                () -> WrapperInteractionService.validateSelection(menu, List.of("sol", "hacan")));
        assertThrows(ResponseStatusException.class, () -> WrapperInteractionService.validateSelection(menu, List.of()));
    }

    @Test
    void modalSubmissionCannotInventFieldsOrIgnoreOriginalConstraints() {
        var modal = Modal.create("game_setup", "Setup")
                .addComponents(Label.of(
                        "Name",
                        TextInput.create("name", TextInputStyle.SHORT)
                                .setRequired(true)
                                .setMinLength(2)
                                .setMaxLength(10)
                                .build()))
                .build();
        assertDoesNotThrow(() -> WrapperInteractionService.validateFields(modal, Map.of("name", "TI4")));
        assertThrows(
                ResponseStatusException.class,
                () -> WrapperInteractionService.validateFields(modal, Map.of("name", "x")));
        assertThrows(ResponseStatusException.class, () -> WrapperInteractionService.validateFields(modal, Map.of()));
        assertThrows(
                ResponseStatusException.class,
                () -> WrapperInteractionService.validateFields(modal, Map.of("name", "TI4", "admin", "true")));
    }
}
