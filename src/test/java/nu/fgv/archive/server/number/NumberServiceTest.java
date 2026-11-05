/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.archive.server.number;

import nu.fgv.archive.server.number.NumberService.NumberInput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pasted lists of numbers: plain titles, numbered lines, melodies in parentheses and spreadsheet columns.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class NumberServiceTest {

    @Test
    void parsesPastedLines() {
        assertThat(NumberService.parseLine("Ouvertyr")).isEqualTo(new NumberInput("Ouvertyr", null, null, null));
        assertThat(NumberService.parseLine("3. Kärleksduetten (Something stupid)"))
                .isEqualTo(new NumberInput("Kärleksduetten", "Something stupid", null, null));
        assertThat(NumberService.parseLine("12 Finalen")).isEqualTo(new NumberInput("Finalen", null, null, null));
        assertThat(NumberService.parseLine("Hovets klagan\tYesterday (The Beatles)\tÅsa Åkesson\t"))
                .isEqualTo(new NumberInput("Hovets klagan", "Yesterday (The Beatles)", "Åsa Åkesson", null));
        assertThat(NumberService.parseLine("1984 års visa")).isEqualTo(new NumberInput("1984 års visa", null, null, null));
    }
}
