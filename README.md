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

# How to use LENSE:

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

In the above diagram, you're responsible for two parts: extracting features, and providing text to query humans with.

Let's imagine you want to make a function like this:

`public String[] labelTweetTokens(String[] tokens)`

And you want the returned `String[]` array to be of the same length as tokens, where each element is drawn from the global array:

```
String[] tags = {
    "PERSON",
    "COMPANY",
    "OTHER"
};
```

Your first task is to build a [loglinear](https://github.com/keenon/loglinear) model of the tokens.

```
public String[] labelTweetTokens(String[] tokens) {

    // Create empty graphical model
    
    GraphicalModel model = new GraphicalModel();
    
    // Add factors to the model
    
    for (int i = 0; i < tokens.length; i++) {
    
        // Add the unary factor
        
        GraphicalModel.Factor f = model.addFactor(new int[]{i}, new int[]{tags.length}, (assignment) -> {
            return new ConcatVector();
        });
    }
}

```
