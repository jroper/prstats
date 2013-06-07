$(window).load(function() {
    $("#getprstats").click(function() {
        // Tell the server what the username/password to use should be - because we don't want to put it in the URL of the iframe
        $.ajax({
            type: "POST",
            url: "/ghcreds",
            contentType: "application/json",
            data: JSON.stringify({
                "username": $("#username").val(),
                "password": $("#password").val()
            }),
            success: function() {
               $("#results").html("<iframe style='height: 0; width: 0;' " +
                   "src='/ghstats?repo=" + $("#repo").val() + "&exclude=" + $("#exclude").val() + "'></iframe><pre id='console'></pre>")
            }
        })
    });
});

var cometMessage = function(event) {
    var console = $("#console")
    console.text(console.text() + "\n" + event)
}