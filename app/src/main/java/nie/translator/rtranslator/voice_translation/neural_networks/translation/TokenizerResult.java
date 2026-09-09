/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation.neural_networks.translation;

public class TokenizerResult {
    private int[] inputIDs;
    private int[] attentionMask;

    public TokenizerResult(int[] inputIDs, int[] attentionMask) {
        this.inputIDs = inputIDs;
        this.attentionMask = attentionMask;
    }


    public int[] getInputIDs() {
        return inputIDs;
    }

    public void setInputIDs(int[] inputIDs) {
        this.inputIDs = inputIDs;
    }

    public int[] getAttentionMask() {
        return attentionMask;
    }

    public void setAttentionMask(int[] attentionMask) {
        this.attentionMask = attentionMask;
    }
}
