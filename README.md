# LENSE: Learning from Expensive, Noisy, Slow Experts

This is the main repo for the LENSE production version.

To add as a Maven dependency:

    <dependency>
        <groupId>com.github.keenon</groupId>
        <artifactId>lense</artifactId>
        <version>1.0</version>
    </dependency>

## Useful Links:

- [the original paper](http://arxiv.org/abs/1506.03140)
- [the experiments from the NIPS paper](http://github.com/keenon/lense-experiments)
- [the developement repo (no longer maintained)](http://github.com/keenon/lense-dev)

# A brief tutorial:

## What is LENSE?

Basically, you can think of LENSE as a magical MAP inference tool for log-linear PGMs.
If those are unfamiliar concepts to you, go watch the lectures from [Daphne Koller's excellent Coursera course on PGMs](https://class.coursera.org/pgm/lecture/preview).

For a more academic treatment of the following content, see [the NIPS paper](http://arxiv.org/abs/1506.03140).

## Why should I use LENSE?

LENSE uses a mixture of machine learning and human workers (which it can recruit from Mechanical Turk for you) to get a
very accurate system that you can deploy right away.
LENSE starts by querying humans every time you ask it a question, but over time it learns to take over from the humans.
This means that over time your marginal costs fall!

![scaling costs graph](http://keenon.github.io/assets/lense/scaling_costs.png)

## An example use case:

Suppose you're interested in doing sequence labeling on Tweets.
For those unfamiliar with sequence labeling, it's a task where you split a sentence into individual words (tokens, in the jargon)
and you're trying to label each work (token) into one of several classes.
Suppose you want to know which tokens correspond to people, businesses, or other, so that you can find the general sentiment of tweets that talk about a certain business or celebrity.
You want a very accurate system, you want it launched today, but you have no training data.
What to do?
Use LENSE!

Before we continue, LENSE depends on [loglinear](https://github.com/keenon/loglinear), a fast log-linear-model library written in Java.
We assume some familiarity with that code in this tutorial.

![twitter labeling example](http://keenon.github.io/assets/lense/ner_example.png)

In the above diagram, you're responsible for two parts of the flow: extracting features, and providing HTML to query humans with.

Let's imagine you want to make a function like this:

`public String[] labelTweetTokens(String[] tokens)`

And you want the returned `String[]` array to be of the same length as tokens, where each element is drawn from the global array:

```java
String[] tags = {
    "PERSON",
    "COMPANY",
    "OTHER"
};
```

You need to do two things before you can do a one-liner to get fast, accurate answers from LENSE:

- write a function to create `GraphicalModel` objects (log-linear models) with factors that contain reasonable feature vectors
- write a function to annotate each variable in your `GraphicalModel` with HTML that LENSE can show to humans when it wants to ask for a human opinion.

Your first task is to build a [loglinear](https://github.com/keenon/loglinear) model of the tokens.
We're going to build a linear chain CRF, with unary and binary factors.
The unary factors will have bias features for each tag, and token+tag features.
The binary factors will only have bias features for each tag+tag combo.
For questions about this code please refer to [loglinear documentation](https://github.com/keenon/loglinear).

```java
public String[] labelTweetTokens(String[] tokens) {
    GraphicalModel model = createModel(tokens);
    // this will be filled in in the rest of the tutorial
    return new String[]{};
}

ConcatVectorNamespace namespace = new ConcatVectorNamespace();

private GraphicalModel createModel(String[] tokens) {

    // Create empty graphical model
    
    GraphicalModel model = new GraphicalModel();
    
    // Add factors to the model
    
    for (int i = 0; i < tokens.length; i++) {
    
        // Add the unary factor
        
        model.addFactor(new int[]{i}, new int[]{tags.length}, (assignment) -> {
            ConcatVector features = new ConcatVector();
            namespace.setDenseFeature(features, "BIAS"+tag[assignment[0]], new double[] {1.0});
            namespace.setSparseFeature(features, "word"+tokens[i], tag[assignment[0]], 1.0);
            return features;
        });
        
        // Add the binary factor, if we're not at the end of the sequence
        
        if (i == tokens.length-1) continue;
        
        model.addFactor(new int[]{i, i+1}, new int[]{tags.length, tags.length}, (assignment) -> {
            ConcatVector features = new ConcatVector();
            namespace.setDenseFeature(features, "BIAS"+tag[assignment[0]]+tag[assignment[1]], new double[] {1.0});
            return features;
        });
    }
    
    return model;
}

```

The next task, now that we have a model, is to annotate each variable with metadata to tell LENSE what HTML to use to ask
a human about the value of that variable, if it chooses to do so. We encode this as a string on the metadata for a variable,
using loglinear. The encoding is a JSON string. The basic format for the metadata for each variable in the model looks like
this:

```json
{
    "html": "<h1>Title</h1>Are you feeling lucky, punk?",
    "choices": [
        "yes",
        "no",
        "maybe"
    ]
}
```

Here's the Java code to generate sequence labelling questions for each token, using the SimpleJSON library from google.

```java
public String[] labelTweetTokens(String[] tokens) {
    GraphicalModel model = createModel(tokens);
    annotateWithHumanQuestions(model, tokens);
    // this will be filled in in the rest of the tutorial
    return new String[]{};
}

private void annotateWithHumanQuestions(GraphicalModel model, String[] tokens) {
    for (int i = 0; i < tokens.length; i++) {
        Map<String,String> metadata = model.getVariableMetaDataByReference(i);
        
        // Add the query data
        JSONObject queryData = new JSONObject();

        String html = "What kind of thing is the highlighted word?<br>";
        html +="<span>";
        for (int j = 0; j < tokens.size(); j++) {
            if (j == i) html +="<b>";
            html += " "+tokens.get(j);
            if (j == i) html +="</b>";
        }
        html +="</span>";
        queryData.put("html", html);

        JSONArray choices = new JSONArray();
        for (String tag : tags) {
            choices.add(tag);
        }

        queryData.put("choices", choices);

        metadata.put(MTurkHumanSource.QUERY_JSON, queryData.toJSONString());
    }
}
```

Now that we have a way to generate properly annotated GraphicalModels, our job is done. We can just hand these models to
LENSE and it will take care of the rest.

```java
// The constructor for LenseWithRetraining takes a bunch of arguments, we'll talk about those in a second
LenseWithRetraining lense = new LenseWithRetraining(...);
Object monitorLockForThreadSafety = new Object();

public String[] labelTweetTokens(String[] tokens) {
    GraphicalModel model = createModel(tokens);
    annotateWithHumanQuestions(model, tokens);
    int[] map = lense.getMAP(model, monitorLockForThreadSafety);
    assert(map.length == tokens.length);
    String[] labels = new String[tokens.length];
    for (int i = 0; i < labels.length; i++) {
        labels[i] = tags[map[i]];
    }
    return labels;
}
```

The only thing we left out of that explanation is what arguments LenseWithRetraining takes.

## More examples, and runnable code:

Check out the [repo with the experiments](http://github.com/keenon/lense-experiments) we did for [the original paper](http://arxiv.org/abs/1506.03140).
All those examples are runnable, and we even have a bunch of frozen human input that you can play back automatically on 3 different datasets, so you can play around without spending real money on MTurk.

# Further research

We're academics, and our primary reason for publishing LENSE was to make it easy to do follow up research.
Unfortunately, we're academics, so we haven't really documented how to do that yet :/.
Basically, look at `GamePlayer` to write new decision algorithms for LENSE to use.
Look at `HumanSource` for introducing new kinds of information sources.
