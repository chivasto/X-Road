var oTsps, oTspsApproved;

function uploadCallback(response) {
    if (!response.success) {
        showMessages(response.messages);
        return;
    }

    var row1 = $("<tr>")
        .append($("<td>").text(_("common.hash")).addClass("semibold"))
        .append($("<td>").text(response.data.hash));

    var row2 = $("<tr>")
        .append($("<td>").text(_("common.generated")).addClass("semibold"))
        .append($("<td>").text(response.data.generated_at));

    var details = $("<table>")
        .append(row1)
        .append(row2)
        .addClass("details")
        .css("margin", "1em 0");

    var confirmParams = {
        details: $("<p>").append(details).html()
    };

    $("#anchor_upload_dialog").dialog("close");

    confirm("anchor.upload_confirm", confirmParams, function() {
        $.post(action("anchor_apply"), null, function() {
            $(".anchor-hash").text(response.data.hash);
            $(".anchor-generated_at").text(response.data.generated_at);
        }, "json");
    });
}

function initTables() {
    var opts = scrollableTableOpts(200);
    opts.sDom = "t";
    opts.aoColumns = [
        { "mData": "name" },
        { "mData": "url" }
    ];
    opts.asStripeClasses = [];

    oTsps = $("#tsps").dataTable(opts);

    opts = scrollableTableOpts(200);
    opts.sDom = "t";
    opts.aoColumns = [
        { "mData": "name" }
    ];
    opts.asStripeClasses = [];

    oTspsApproved = $("#tsps_approved").dataTable(opts);

    $("#tsps tbody tr").live("click", function() {
        oTsps.setFocus(0, this);
        $("#tsp_delete").enable();
    });

    $("#tsps_approved tbody tr").live("click", function() {
        oTspsApproved.setFocus(0, this);
    });
}

function initAnchorActions() {
    $("#anchor_upload_dialog").initDialog({
        autoOpen: false,
        modal: true,
        height: 200,
        width: 600,
        buttons: [
        {
            text: _("common.upload"),
            id: "anchor_upload_submit",
            disabled: "disabled",
            click: function() {
                $("#anchor_upload_form").submit();
            }
        },
        {
            text: _("common.cancel"),
            click: function() {
                $(this).dialog("close");
            }
        }]
    });

    $("#anchor_upload_file").change(function() {
        if ($(this).val() != "") {
            $("#anchor_upload_submit").enable();
        }
    }).val("");

    $("#anchor_upload").click(function() {
        $("#anchor_upload_file").val("");
        $("#anchor_upload_dialog").dialog("open");
    });

    $("#anchor_download").click(function() {
        window.location = action("anchor_download");
    });
}

function initTspActions() {
        $("#tsp_add_dialog").initDialog({
        autoOpen: false,
        width: 600,
        height: 400,
        modal: true,
        open: function() {
            oTspsApproved.fnAdjustColumnSizing();

            $.get(action("tsps_approved"), function(response) {
                oTspsApproved.fnReplaceData(response.data);
            });
        },
        buttons: [
        {
            text: _("common.ok"),
            click: function() {
                var selected = oTspsApproved.getFocusData();
                var params = {
                    name: selected.name,
                    url: selected.url
                };

                $.post(action("tsp_add"), params, function(response) {
                    oTsps.fnReplaceData(response.data);
                });

                $(this).dialog("close");
            }
        },
        {
            text: _("common.cancel"),
            click: function() {
                $(this).dialog("close");
            }
        }]
    });

    $("#tsp_add").click(function() {
        $("#tsp_add_dialog").dialog("open");
    });

    $("#tsp_delete").click(function() {
        var params = {
            name: oTsps.getFocusData().name
        };

        $.post(action("tsp_delete"), params, function(response) {
            oTsps.fnReplaceData(response.data);
        });
    }).disable();
}

function initAsyncActions() {
    $("#async_edit").click(function() {
        var params = {
            base_delay: $("#async_period").val(),
            max_delay: $("#async_max_period").val(),
            max_senders: $("#async_parallel").val()
        };

        $.post(action("async_params_edit"), params, null, "json");
    });
}

function initInternalSSLActions() {
    $("#cert_details_dialog").initDialog({
        autoOpen: false,
        modal: true,
        width: 710,
        height: 580,
        open: function() {
            var dialog = this;

            $.get(action("internal_ssl_cert_details"), function(response) {
                $("#dump", dialog).text(response.data.dump).scrollTop(0);
                $("#hash", dialog).text(response.data.hash);
            }, "json");
        },
        buttons: [
        {
            text: _("common.ok"),
            click: function() {
                $(this).dialog("close");
            }
        }]
    });

    $("#cert_details").click(function(event) {
        event.preventDefault();

        $("#cert_details_dialog").dialog("open");
    });

    $("#export_internal_ssl_cert").click(function() {
        location.href = action("internal_ssl_cert_export");
    });

    $("#generate_internal_ssl").click(function(event) {
        event.preventDefault();

        confirm("sysparams.index.generate_internal_ssl_confirm", null,
                function() {
            $.get(action("internal_ssl_generate"), function(response) {
                if (response.data.hash) {
                    $("#internal_ssl_cert_hash").text(response.data.hash);
                    $("#cert_details, #export_internal_ssl_cert").enable();
                }
            }, "json");
        });
    });
}

function populate() {
    $.get(action("sysparams"), function(response) {
        if (response.data.anchor) {
            $(".anchor-hash").text(response.data.anchor.hash);
            $(".anchor-generated_at").text(response.data.anchor.generated_at);
        }

        if (response.data.tsps) {
            oTsps.fnReplaceData(response.data.tsps);
        }

        if (response.data.async) {
            $("#async_period").val(response.data.async.base_delay);
            $("#async_max_period").val(response.data.async.max_delay);
            $("#async_parallel").val(response.data.async.max_senders);
        }

        if (response.data.internal_ssl_cert) {
            $("#internal_ssl_cert_hash").text(response.data.internal_ssl_cert.hash);
            $("#cert_details, #export_internal_ssl_cert").enable();
        } else {
            $("#cert_details, #export_internal_ssl_cert").disable();
        }
    }, "json");
}

$(document).ready(function() {
    initTables();
    initAnchorActions();
    initTspActions();
    initAsyncActions();
    initInternalSSLActions();

    populate();
});
