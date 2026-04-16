<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>Balansrapport</h2>
  <p>${selectionLabel}</p>
  <table>
    <thead>
      <tr>
        <#list tableHeaders as header>
          <th>${header}</th>
        </#list>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <#assign isSummary = typedRows[row?index].summaryRow>
        <tr<#if isSummary> style="font-weight: bold; border-top: 1px solid #333;"</#if>>
          <td>${row[0]}</td>
          <td style="text-align: right;">${row[1]}</td>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
