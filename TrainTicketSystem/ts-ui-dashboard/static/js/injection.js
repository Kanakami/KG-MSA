$("#do_injection").click(function() {
    $.ajax({
        type: "get",
        url: "/seat/injection/on",
        contentType: "application/json",
        dataType: "json",
        xhrFields: {
            withCredentials: true
        },
        success: function (result) {
            var obj = result;
            if(obj["status"] == true){
                $("#injection_status").text("Injected");
            }
        }
    });
});

$("#cancel_injection").click(function() {
    $.ajax({
        type: "get",
        url: "/seat/injection/off",
        contentType: "application/json",
        dataType: "json",
        xhrFields: {
            withCredentials: true
        },
        success: function (result) {
            var obj = result;
            if(obj["status"] == true){
                $("#injection_status").text("Non - Injected");
            }
        }
    });
});