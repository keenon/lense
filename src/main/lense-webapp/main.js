$.urlParam = function(name){
    var results = new RegExp('[\?&]' + name + '=([^&#]*)').exec(window.location.href);
    if (results==null){
       return null;
    }
    else{
       return decodeURIComponent(results[1]) || 0;
    }
};

$(function () {
    "use strict";

    // Get Worker information

    var assignmentId = $.urlParam("assignmentId");
    var hitId = $.urlParam("hitId");
    var turkSubmitTo = $.urlParam("turkSubmitTo");
    var workerId = $.urlParam("workerId");

    // Get DOM elements

    var content = $('#content');
    var bonus = $('#bonus');
    var readyButton = $('#ready');
    var retainer = $('#retainer');
    var mTurkSubmitForm = $("#successForm");
    var mTurkReturnForm = $("#returnForm");

    // Setup submit form

    mTurkSubmitForm.attr("action", turkSubmitTo+"/mturk/externalSubmit");
    $("#assignmentId").val(assignmentId);
    $("#hitId").val(hitId);

    // Setup return form

    mTurkReturnForm.attr("action", turkSubmitTo+"/mturk/return");
    $("#hitIdReturn").val(hitId);

    /////////////////////////////////////////
    // WORKER WEB-SOCKET
    /////////////////////////////////////////

    var ws = {};

    /**
     * This creates and sets up the web socket
     */
    function connectSocket() {
        var currentURL = document.location.href;
        var method = currentURL.split("://")[0];
        var addr = currentURL.split("://")[1].split("/")[0].split("?")[0];
        console.log(method+","+addr);

        var webSocketAddr = "";
        if (method === "https") {
            webSocketAddr = "wss://"+addr+"/work-socket";
        }
        else {
            webSocketAddr = "ws://"+addr+"/work-socket";
        }
        ws = new WebSocket(webSocketAddr);

        /**
         * This gets called when the socket first succeeds in connecting to the server.
         */
        ws.onopen = function() {
            ws.send(JSON.stringify({
                type: 'ready-message',
                "assignment-id": assignmentId,
                "hit-id": hitId,
                "worker-id": workerId
            }));
        };

        /**
         * This gets called when a message is received.
         * @param messageObject the message we got, as an AtmosphereResource
         */
        ws.onmessage = function (messageObject) {
            var message = messageObject.data;

            console.log("on message: "+JSON.stringify(message));
            try {
                var json = jQuery.parseJSON(message);

                // If we're receiving a query

                if (json['type'] !== undefined && json.type === "query") {
                    renderQuery(json['payload'], function(closureChoice) {
                        console.log("Choosing "+closureChoice);

                        ws.send(JSON.stringify({
                            type: "query-response",
                            "query-response": closureChoice
                        }));

                        content.css({
                            height: content.height()
                        });
                    });
                }

                if (json['type'] !== undefined && json.type === "job-cancelled") {
                    content.html("There's no more work from the server for the moment. KEEP THIS TAB OPEN SO WE CAN PAY YOU, but feel free to browse to other sites in other tabs. We'll alert you if more work shows up.");
                }

                // If we're being asked to shut down early

                if (json['type'] !== undefined && json.type === "early-termination") {
                    content.html("The server has decided to give you the rest of your time back! You're going to get this HIT approved, plus all the bonus, and you won't have to wait till the end of the clock. Congratulations.");
                    setTimeout(function() {
                        workComplete();
                    }, 4000);
                }

                // If we're receiving information about total queries answered

                if (json['total-queries-answered'] !== undefined) {
                    var totalAnswered = json['total-queries-answered'];
                    console.log("Total answered: "+totalAnswered);

                    var bonusAmount = 3 + (totalAnswered / 500);

                    bonus.html('$'+bonusAmount.toFixed(3));
                }

                // If we're receiving information about the total time this session will be for

                if (json['on-call-duration'] !== undefined) {
                    var onCallDuration = json['on-call-duration'];
                    var startTimeMillis = (new Date()).getTime();
                    var interval = setInterval(function() {
                        var currentTimeMillis = (new Date()).getTime();
                        var elapsedMillis = currentTimeMillis - startTimeMillis;
                        var remainingMillis = onCallDuration - elapsedMillis;

                        // If we're terminating the interval

                        if (remainingMillis < 0) {
                            if (!document.hasFocus()) {
                                alert("Your time is up! Collect your earnings!");
                            }

                            var retainerContainer = $("#retainer-container");
                            retainerContainer.html('');
                            var input = $('<button class="collect">Collect your earnings!</button>');
                            input.css({
                                "z-index": 100,
                                "position": "relative"
                            });
                            input.click(function() {
                                console.log("Turning in results");
                                ws.send(JSON.stringify({ request: 'turn-in' }));
                                workComplete();
                            });

                            input.appendTo(retainerContainer);
                            window.clearInterval(interval);
                        }

                        // If we're still counting down, then compute the proper display

                        else {

                            // Compute the hh:mm:ss display

                            var totalSeconds = Math.ceil(remainingMillis / 1000);
                            var totalMinutes = Math.floor(totalSeconds / 60);
                            var hours = Math.floor(totalMinutes / 60);
                            var seconds = totalSeconds % 60;
                            var minutes = totalMinutes % 60;
                            var to2Tokens = function(number) {
                                if (number < 10) return "0"+number;
                                else return ""+number;
                            };
                            retainer.html(to2Tokens(hours)+":"+to2Tokens(minutes)+":"+to2Tokens(seconds));
                        }
                    }, 1000);
                }
            } catch (e) {
                console.log(e);
                console.log('This doesn\'t look like a valid JSON: ', message);
            }
        };

        var alreadyClosed = false;
        /**
         * This gets called when the request is terminated by the server.
         */
        ws.onclose = function() {
            // Guard against infinite recursive stacks
            if (!alreadyClosed) {
                alreadyClosed = true;
            }
        };

        /**
         * This gets called when some kind of error occurs with the socket. Can happen if we attempt to connect and the
         * server is not available.
         * @param err
         */
        ws.onerror = function(err) {
            console.log("on error: "+err);

            content.html($('<p>', { text: 'Sorry, but there\'s some problem with your '
            + 'socket or the server is down. We\'re going to pay you for the work you did so far, minus the retainer. Turning in HIT in 6 seconds.' }));

            setTimeout(function() {
                workComplete('timeout');
            }, 6000);
        };
    }

    /////////////////////////////////////////
    // QUERY RENDERING
    /////////////////////////////////////////

    /**
     * This renders a query, and sets up keyboard input hooks for replying to the query.
     * @param json the object we received from the server describing the query
     * @param callback the callback we respond with once we've answered the query
     */
    function renderQuery(json, callback) {
        if (!document.hasFocus()) {
            alert("There's a task available for you now");
        }

        // We need to create a multiclass question here
        content.css({
            height: 'auto'
        });

        // Render the question

        content.html(json.html+"<br>");

        // Render the choices

        var keys = [];
        var refKeys = ['a','b','c','d','e','f','g','h','i','j','k','l','m','n','o','p','q','r','s','t','u','v','w','x','y','z'];

        for (var choiceID in json.choices) {
            var index = parseInt(choiceID);
            var choice = json.choices[choiceID];
            var b = $('<button/>', {class: 'choice'});
            b.html(choice);

            var shortcut = $('<span/>', {class: 'key'});

            var key = choice.toLowerCase().charAt(0);
            if (!$.inArray(key, keys)) {
                for (var i in refKeys) {
                    key = refKeys[i];
                    if (!$.inArray(key, keys)) break;
                }
            }
            keys.push(key);

            b.append(shortcut);
            shortcut.html(key);

            content.append(b);

            var makeChoice = function(closureChoice) {
                $(document).unbind("keydown");
                $(document).unbind("keyup");

                callback(closureChoice);
            };

            // I hate Javascript so much. I just can't even describe it.
            $(document).keyup((function(closureChoice, closureKey) {
                return function(e) {
                    var index = 65+refKeys.indexOf(closureKey);
                    if (e.which == index) {
                        makeChoice(closureChoice);
                    }
                }
            })(index, key));

            b.click((function(closureChoice) {
                return function() {
                    makeChoice(closureChoice);
                }
            })(index));

            $(document).keydown((function(closureChoice, closureKey, closureButton) {
                return function(e) {
                    var index = 65+refKeys.indexOf(closureKey);
                    if (e.which == index) {
                        closureButton.addClass("hover");
                        $(document).unbind("keypress");
                    }
                }
            })(index, key, b));
        }
    }

    /**
     * This submits the form to MTurk, so that the worker can get paid.
     */
    function workComplete() {
        mTurkSubmitForm.submit();
    }

    /////////////////////////////////////////
    // INITIALIZATION
    /////////////////////////////////////////

    // This means that we're just previewing the HIT, so we can't allow somebody to accept it yet.
    if (assignmentId == "ASSIGNMENT_ID_NOT_AVAILABLE") {
        readyButton.html("Accept the HIT to get started!");
        readyButton.addClass("disabled");
    }
    // This means we're actually in the HIT, so we can allow people to accept it
    else {
        readyButton.click(function() {
            bonus.html("$3.000");

            var instructions = $("#instructions");
            var instructionsHeader = $("#instructions-header");
            instructionsHeader.animate({
                height: 0,
            }, 200, "swing", function() {
                instructionsHeader.remove();
            });
            readyButton.animate({
                height: 0,
            }, 200, "swing", function() {
                readyButton.remove();
            });

            setTimeout(function() {
                console.log("Creating a connection");
                connectSocket();
            }, 200);
        });
    }
});
