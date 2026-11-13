-- The stored key is already the nick lower-cased by Java, so only the lookalike
-- map is left to apply. Both strings must stay equal to Nicks.LOOKALIKES and
-- Nicks.LATIN_EQUIVALENTS.
update player set nick_key = translate(
        nick_key,
        U&'01i\0430\0431\0432\0433\0435\0437\043A\043C\043D\043E\0440\0441\0442\0443\0445\0448\044C',
        'olla6bre3kmhopctyxwb');
